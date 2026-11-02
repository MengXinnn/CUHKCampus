package com.cuhkcampus.integration;
import com.cuhkcampus.agent.AgentData;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


public class BrowserSession implements AutoCloseable {
    private final Process process;
    private final Path profile;
    public final Cdp cdp;
    public BrowserSession(AgentData data,String chrome,boolean headless) throws IOException,InterruptedException {
        profile=Files.createTempDirectory("campus-browser-");
        int port;
        try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var args=new java.util.ArrayList<>(List.of(chrome,"--remote-debugging-address=127.0.0.1","--remote-debugging-port="+port,"--user-data-dir="+profile,"--no-first-run","--no-default-browser-check"));
        if(headless){args.add("--headless=new");args.add("--window-size=1440,1000");}
        args.add("about:blank");
        process=new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String ws=null;
        try {
            var http=HttpClient.newHttpClient();
            for(int n=0;n<50&&ws==null;n++) {
                try {
                    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/json/list")).timeout(Duration.ofSeconds(1)).build(),HttpResponse.BodyHandlers.ofString());
                    for(Object item:(List<?>)data.parse(response.body()))if(item instanceof Map<?,?> page&&"page".equals(page.get("type"))) {ws=(String)page.get("webSocketDebuggerUrl");break;}
                }catch(IOException ignored){}
                if(ws==null)Thread.sleep(200);
            }
            if(ws==null)throw new IOException("Chrome 启动超时");
            cdp=Cdp.connect(data,URI.create(ws));
            cdp.call("Network.enable",Map.of());
        }catch(Exception e){process.destroy();delete();throw e;}
    }
    public void navigate(String url){cdp.call("Page.navigate",Map.of("url",url));}
    public Object evaluate(String expression){
        var reply=cdp.call("Runtime.evaluate",Map.of("expression",expression,"returnByValue",true,"awaitPromise",true));
        if(reply.get("error")!=null)throw new IllegalStateException("浏览器执行失败："+reply.get("error"));
        var result=(Map<?,?>)reply.get("result");
        if(result.get("exceptionDetails")!=null)throw new IllegalStateException("网页读取失败："+result.get("exceptionDetails"));
        return ((Map<?,?>)result.get("result")).get("value");
    }
    public void close(){try{cdp.close();}finally{process.destroy();try{process.waitFor(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(process.isAlive())process.destroyForcibly();delete();}}
    private void delete(){try(var paths=Files.walk(profile)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){}}
    public static class Cdp implements WebSocket.Listener {
        private final AgentData data;
        private final BlockingQueue<Map<String,Object>> messages=new LinkedBlockingQueue<>();
        private final AtomicInteger ids=new AtomicInteger();
        private final StringBuilder text=new StringBuilder();
        private WebSocket socket;

        private Cdp(AgentData data) {this.data=data;}

        public static Cdp connect(AgentData data,URI uri) {
            Cdp cdp=new Cdp(data);
            cdp.socket=HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri,cdp).join();
            return cdp;
        }

        public Map<String,Object> call(String method,Object params) {
            int id=ids.incrementAndGet();
            socket.sendText(data.json(Map.of("id",id,"method",method,"params",params)),true).join();
            Instant deadline=Instant.now().plusSeconds(5);
            while(Instant.now().isBefore(deadline)) {
                Map<String,Object> message;
                try {message=messages.poll(200,TimeUnit.MILLISECONDS);}
                catch(InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Chrome 通信已中断",error);
                }
                if(message!=null&&message.get("id") instanceof Number responseId&&responseId.intValue()==id)
                    return message;
            }
            throw new IllegalStateException("Chrome 通信超时");
        }

        public void close() {try {socket.sendClose(WebSocket.NORMAL_CLOSURE,"done").join();} catch(RuntimeException ignored) {}}

        @Override public void onOpen(WebSocket webSocket) {webSocket.request(1);}

        @SuppressWarnings("unchecked")
        @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence fragment,boolean last) {
            text.append(fragment);
            if(last) {
                if(data.parse(text.toString()) instanceof Map<?,?> message)
                    messages.add((Map<String,Object>)message);
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
