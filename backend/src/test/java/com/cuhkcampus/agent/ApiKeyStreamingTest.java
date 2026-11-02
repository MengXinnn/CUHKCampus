package com.cuhkcampus.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ApiKeyStreamingTest {
    @Test @Timeout(10) void apiKeyStreamAlsoDeliversBeyond256BeforeCompletion() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var received=new CountDownLatch(1);var delivered=new AtomicBoolean();
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try(var body=exchange.getResponseBody()){
                for(int i=0;i<320;i++){
                    body.write("data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));body.flush();
                }
                try{delivered.set(received.await(1200,TimeUnit.MILLISECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}
                body.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));body.flush();
            }
        });server.start();
        try{
            var model=OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1").apiKey("test").model("test").maxRetries(0).build()).build();
            var client=ChatClient.builder(model).build();int chunks=0;
            for(String text:client.prompt("synthetic").stream().content().toIterable())if(++chunks==257)received.countDown();
            assertEquals(320,chunks);assertTrue(delivered.get(),"API-key stream stalled at prefetch boundary");
        }finally{server.stop(0);}
    }
}
