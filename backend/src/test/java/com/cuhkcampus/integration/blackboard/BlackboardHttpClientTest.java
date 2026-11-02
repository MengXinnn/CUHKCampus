package com.cuhkcampus.integration.blackboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlackboardHttpClientTest {
    private HttpServer server;
    private BlackboardHttpClient client;
    private final List<String> requests = new ArrayList<>();
    private static final String COOKIE = "s_session_id=verification-session; JSESSIONID=verification-session";

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new BlackboardHttpClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), new ObjectMapper());
    }
    @AfterEach void stop() { server.stop(0); }

    private void json(String path, int status, String body) {
        server.createContext(path, exchange -> {
            requests.add(exchange.getRequestURI().toString());
            assertEquals(COOKIE, exchange.getRequestHeaders().getFirst("Cookie"));
            var bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test void followsEveryRelativeNextPageAndKeepsSessionOnEachRequest() {
        server.createContext("/learn/api/public/v1/users/me/courses", exchange -> {
            String query=exchange.getRequestURI().getQuery();
            requests.add(query);
            assertEquals(COOKIE, exchange.getRequestHeaders().getFirst("Cookie"));
            String body=query!=null && query.contains("offset=2")
                    ? "{\"results\":[{\"courseId\":\"_course3_1\"}]}"
                    : "{\"results\":[{\"courseId\":\"_course1_1\"},{\"courseId\":\"_course2_1\"}],\"paging\":{\"nextPage\":\"/learn/api/public/v1/users/me/courses?limit=2&offset=2\"}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        var rows=client.list(COOKIE,"/learn/api/public/v1/users/me/courses?limit=2");
        assertEquals(List.of("_course1_1","_course2_1","_course3_1"), rows.stream().map(row->row.get("courseId")).toList());
        assertEquals(List.of("limit=2","limit=2&offset=2"), requests);
    }

    @Test void readsNestedFolderChildrenWithAttachmentMetadataAndExactDownloadBytes() {
        json("/learn/api/public/v1/courses/_course1_1/contents",200,"{\"results\":[{\"id\":\"_folder1_1\",\"hasChildren\":true,\"contentHandler\":{\"id\":\"resource/x-bb-folder\"}}]}");
        // More specific contexts are selected by HttpServer's longest-prefix routing.
        json("/learn/api/public/v1/courses/_course1_1/contents/_folder1_1/children",200,"{\"results\":[{\"id\":\"_doc1_1\",\"body\":\"<p>Course material</p>\",\"hasChildren\":false,\"contentHandler\":{\"id\":\"resource/x-bb-document\"}}]}");
        json("/learn/api/public/v1/courses/_course1_1/contents/_doc1_1/attachments",200,"{\"results\":[{\"id\":\"_file1_1\",\"fileName\":\"lecture.pdf\",\"mimeType\":\"application/pdf\"}]}");
        byte[] source="%PDF-1.7\nverification bytes\n".getBytes(StandardCharsets.US_ASCII);
        server.createContext("/learn/api/public/v1/courses/_course1_1/contents/_doc1_1/attachments/_file1_1/download", exchange->{
            assertEquals(COOKIE,exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Location","/bbcswebdav/verification-file");
            exchange.sendResponseHeaders(302,-1);exchange.close();
        });
        server.createContext("/bbcswebdav/verification-file", exchange->{
            assertEquals(COOKIE,exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Content-Type","application/pdf");
            exchange.sendResponseHeaders(200,source.length);exchange.getResponseBody().write(source);exchange.close();
        });
        var folders=client.list(COOKIE,"/learn/api/public/v1/courses/_course1_1/contents?limit=100");
        assertEquals(true,folders.getFirst().get("hasChildren"));
        var children=client.list(COOKIE,"/learn/api/public/v1/courses/_course1_1/contents/_folder1_1/children?limit=100");
        assertEquals("_doc1_1",children.getFirst().get("id"));
        var files=client.list(COOKIE,"/learn/api/public/v1/courses/_course1_1/contents/_doc1_1/attachments?limit=100");
        assertEquals("lecture.pdf",files.getFirst().get("fileName"));
        assertArrayEquals(source,client.bytes(COOKIE,"/learn/api/public/v1/courses/_course1_1/contents/_doc1_1/attachments/_file1_1/download"));
    }

    @Test void reportsActualBytesAndTotalDuringRemoteDownload() throws Exception {
        byte[] source=new byte[65536];
        server.createContext("/slow-file",exchange -> {
            assertEquals(COOKIE,exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Content-Type","application/pdf");
            exchange.sendResponseHeaders(200,source.length);
            exchange.getResponseBody().write(source,0,16384);
            exchange.getResponseBody().flush();
            try {Thread.sleep(500);} catch(InterruptedException error) {Thread.currentThread().interrupt();}
            exchange.getResponseBody().write(source,16384,source.length-16384);
            exchange.close();
        });
        var progress=new ArrayList<BlackboardHttpClient.DownloadProgress>();
        var destination=Files.createTempFile("bb-progress-test-",".pdf");
        try {
            client.download(COOKIE,"/slow-file",destination,0,progress::add);
            assertTrue(progress.stream().anyMatch(value->value.loaded()>0 && value.loaded()<source.length && value.total()==source.length),progress.toString());
            assertEquals(new BlackboardHttpClient.DownloadProgress(source.length,source.length),progress.getLast());
            assertArrayEquals(source,Files.readAllBytes(destination));
        } finally {Files.deleteIfExists(destination);}
    }

    @Test void authenticationFailureDoesNotBecomeAnEmptySuccessfulList() {
        json("/learn/api/public/v1/users/me/courses",401,"{\"status\":401,\"message\":\"API request is not authenticated.\"}");
        var error=assertThrows(BlackboardHttpClient.SessionExpiredException.class,()->client.list(COOKIE,"/learn/api/public/v1/users/me/courses?limit=100"));
        assertTrue(error.getMessage().contains("BB 会话已过期"));
    }
}
