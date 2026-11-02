package com.cuhkcampus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChatGptResponsesModelTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    @AfterEach void close() {if(server!=null)server.stop(0);}
    private URI serve(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",handler);server.start();
        return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/responses");
    }
    public static class CoursesTool {
        final AtomicInteger calls=new AtomicInteger();
        @Tool(name="list_courses",description="List courses") public String courses() {calls.incrementAndGet();return "CSC1003";}
    }
    @Test void keepsDeliveringPastPrefetchBoundaryBeforeUpstreamCompletes() throws Exception {
        var beyondBoundary=new java.util.concurrent.CountDownLatch(1);
        var boundaryDeliveredBeforeCompletion=new java.util.concurrent.atomic.AtomicBoolean();
        var endpoint=serve(exchange->{
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var body=exchange.getResponseBody()){
                for(int i=0;i<320;i++){
                    body.write(("data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}\n\n").getBytes(StandardCharsets.UTF_8));body.flush();
                }
                try {boundaryDeliveredBeforeCompletion.set(beyondBoundary.await(1200,java.util.concurrent.TimeUnit.MILLISECONDS));}
                catch(InterruptedException error){Thread.currentThread().interrupt();}
                body.write("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n".getBytes(StandardCharsets.UTF_8));body.flush();
            }
        });
        var client=ChatClient.builder(new ChatGptResponsesModel(()->"test","test",endpoint)).build();
        int count=0;
        for(String chunk:client.prompt("synthetic stream").stream().content().toIterable()){
            if(++count==257)beyondBoundary.countDown();
        }
        assertEquals(320,count);
        assertTrue(boundaryDeliveredBeforeCompletion.get(),"Chunk 257 was withheld until upstream completion instead of being streamed");
    }
    @Test void preservesPageImageAsMultimodalInput() throws Exception {
        var model=new ChatGptResponsesModel(()->"test","test",URI.create("http://localhost/v1/responses"));
        byte[] bytes={1,2,3};
        var media=new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_JPEG,new org.springframework.core.io.ByteArrayResource(bytes));
        var prompt=new Prompt(org.springframework.ai.chat.messages.UserMessage.builder().text("解释这一页").media(media).build());
        var method=ChatGptResponsesModel.class.getDeclaredMethod("input",Prompt.class);
        method.setAccessible(true);
        var input=json.valueToTree(method.invoke(model,prompt));
        assertEquals("input_image",input.get(0).path("content").get(1).path("type").asText());
        assertEquals("data:image/jpeg;base64,AQID",input.get(0).path("content").get(1).path("image_url").asText());
    }
    @Test void streamsTextAndCarriesNativeToolResultsAndReasoning() throws Exception {
        var requests=new ArrayList<JsonNode>();
        var endpoint=serve(exchange->{
            assertEquals("Bearer oauth-test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(json.readTree(exchange.getRequestBody()));
            String events=requests.size()==1?"""
                    data: {"type":"response.completed","response":{"output":[{"type":"reasoning","id":"rs_1","encrypted_content":"encrypted-test-reasoning","summary":[]},{"type":"function_call","id":"fc_1","call_id":"call_1","name":"list_courses","arguments":"{}"}]}}

                    """:"""
                    data: {"type":"response.output_text.delta","delta":"你的课程是 "}

                    data: {"type":"response.output_text.delta","delta":"CSC1003。"}

                    data: {"type":"response.completed","response":{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"你的课程是 CSC1003。"}]}]}}

                    """;
            byte[] bytes=events.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        var tool=new CoursesTool();
        var client=ChatClient.builder(new ChatGptResponsesModel(()->"oauth-test-token","chosen-account-model",endpoint)).build();
        var chunks=client.prompt("我的课程？").tools(tool).stream().content().collectList().block();
        assertEquals("你的课程是 CSC1003。",String.join("",chunks));
        assertEquals(1,tool.calls.get());assertEquals(2,requests.size());
        for(var request:requests) {
            assertFalse(request.path("store").asBoolean());assertTrue(request.path("stream").asBoolean());
            assertEquals("chosen-account-model",request.path("model").asText());
            assertEquals("reasoning.encrypted_content",request.path("include").get(0).asText());
        }
        var continued=requests.get(1).path("input");
        assertEquals("encrypted-test-reasoning",continued.get(1).path("encrypted_content").asText());
        assertEquals("function_call_output",continued.get(3).path("type").asText());
        assertEquals("call_1",continued.get(3).path("call_id").asText());
        assertTrue(continued.get(3).path("output").asText().contains("CSC1003"));
    }
    public static class BrokenTool {
        @Tool(name="broken",description="Fails") public String broken() {throw new IllegalStateException("BB 请求失败（HTTP 403）");}
    }
    @Test void toolFailureNamesToolAndOriginalReason() throws Exception {
        var endpoint=serve(exchange->{
            byte[] bytes="data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"broken\",\"arguments\":\"{}\"}]}}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        var client=ChatClient.builder(new ChatGptResponsesModel(()->"token","model",endpoint)).build();
        var error=assertThrows(RuntimeException.class,()->client.prompt("hello").tools(new BrokenTool()).stream().content().collectList().block());
        assertTrue(error.getMessage().contains("broken"),error.toString());
        assertTrue(error.getMessage().contains("403"),error.toString());
    }
    @Test void emptyAnswerAfterToolIsNotSuccessfulCompletion() throws Exception {
        var rounds=new AtomicInteger();
        var endpoint=serve(exchange->{
            String output=rounds.incrementAndGet()==1 ? "[{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"list_courses\",\"arguments\":\"{}\"}]" : "[]";
            byte[] bytes=("data: {\"type\":\"response.completed\",\"response\":{\"output\":"+output+"}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        var client=ChatClient.builder(new ChatGptResponsesModel(()->"token","model",endpoint)).build();
        var error=assertThrows(RuntimeException.class,()->client.prompt("hello").tools(new CoursesTool()).stream().content().collectList().block());
        assertTrue(error.getMessage().contains("没有返回回答"),error.toString());
    }
    @Test void executesToolFromOutputItemDoneWhenCompletedOutputIsEmpty() throws Exception {
        var rounds=new AtomicInteger();
        var endpoint=serve(exchange->{
            String events=rounds.incrementAndGet()==1
                ? "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"f1\",\"call_id\":\"c1\",\"name\":\"list_courses\",\"arguments\":\"{}\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"
                : "data: {\"type\":\"response.output_text.delta\",\"delta\":\"课程评分回答\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n";
            byte[] bytes=events.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        var tool=new CoursesTool();
        var client=ChatClient.builder(new ChatGptResponsesModel(()->"token","model",endpoint)).build();
        assertEquals("课程评分回答",String.join("",client.prompt("评分怎么算").tools(tool).stream().content().collectList().block()));
        assertEquals(1,tool.calls.get());assertEquals(2,rounds.get());
    }
    @Test void usageLimitAfterTextIsFailure() throws Exception {
        var endpoint=serve(exchange->{
            String events="data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n";
            byte[] bytes=events.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        var model=new ChatGptResponsesModel(()->"oauth-test-token","selected",endpoint);
        var error=assertThrows(ChatGptResponsesModel.InferenceException.class,()->model.call(new Prompt("hello")));
        assertTrue(error.getMessage().contains("subscription_sharing_usage_limit_exceeded"));
    }
    @Test void interruptedStreamDoesNotBecomeSuccessfulAnswer() throws Exception {
        var endpoint=serve(exchange->{
            byte[] bytes="data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });
        var model=new ChatGptResponsesModel(()->"oauth-test-token","selected",endpoint);
        var error=assertThrows(ChatGptResponsesModel.InferenceException.class,()->model.call(new Prompt("hello")));
        assertTrue(error.getMessage().contains("未收到完成事件"));
    }
}
