package com.cuhkcampus.integration;

import com.cuhkcampus.agent.AgentData;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionTest {
    @Test void headlessBrowserReadsRenderedPageAndReportsScriptErrors() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body="<html><body><input id='name'><button onclick=\"document.querySelector('p').textContent=document.querySelector('input').value\">Open</button><p>Ready</p></body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        var data=new AgentData(null);
        String chrome="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
        String url="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            try(var browser=new BrowserSession(data,chrome,true)){
                browser.navigate(url);
                long deadline=System.currentTimeMillis()+5000;
                while(!Boolean.TRUE.equals(browser.evaluate("!!document.querySelector('input')"))&&System.currentTimeMillis()<deadline)Thread.sleep(100);
                browser.evaluate("document.querySelector('input').value='Hello';document.querySelector('button').click();document.cookie='session=browser-test;path=/'");
                assertEquals("Hello",browser.evaluate("document.querySelector('p').textContent"));
                assertThrows(IllegalStateException.class,()->browser.evaluate("throw new Error('fixture error')"));
            }

        }finally{server.stop(0);}
    }
}
