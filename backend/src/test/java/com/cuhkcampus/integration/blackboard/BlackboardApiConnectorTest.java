package com.cuhkcampus.integration.blackboard;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import com.cuhkcampus.integration.AttachmentStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlackboardApiConnectorTest {
    private static final String API="/learn/api/public/v1";
    private static final String COOKIE="s_session_id=test-token";
    private final UUID user=UUID.randomUUID(), course=UUID.randomUUID(), node=UUID.randomUUID();
    private HttpServer server;
    private AgentData data;
    private AttachmentStore files;
    private BlackboardApiConnector connector;
    private BlackboardLoginService login;
    private final List<String> requests=new ArrayList<>();

    private org.springframework.beans.factory.ObjectProvider<BlackboardLoginService> loginProvider(){
        var provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        login=org.mockito.Mockito.mock(BlackboardLoginService.class);
        org.mockito.Mockito.when(provider.getObject()).thenReturn(login);
        return provider;
    }
    @BeforeEach void setup() throws Exception {
        data=spy(new AgentData(mock(JdbcClient.class)));
        files=mock(AttachmentStore.class);
        var cipher=mock(CredentialCipher.class);
        when(cipher.decrypt("encrypted-token")).thenReturn("{\"cookieHeader\":\""+COOKIE+"\"}");
        doReturn(Map.of("credentials",Map.of("encrypted","encrypted-token"))).when(data)
            .one("select credentials from external_connections where user_id=? and provider='BLACKBOARD'",user);
        doReturn(Map.of("id",course,"externalId","_course_1")).when(data)
            .one("select * from courses where id=? and user_id=?",course,user);
        doReturn(Map.of("id",node,"courseId",course,"externalId","_item_1","externalCourseId","_course_1","nodeType","ITEM")).when(data)
            .one("select n.*,c.external_id as external_course_id from content_nodes n join courses c on c.id=n.course_id where n.id=? and c.user_id=?",node,user);
        doReturn(1).when(data).update(anyString(),any(Object[].class));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.start();
        connector=new BlackboardApiConnector(data,cipher,files,"http://127.0.0.1:"+server.getAddress().getPort(),"curl",loginProvider());
    }
    @AfterEach void stop() {server.stop(0);}

    private void json(String path,int status,String body) {
        server.createContext(path,exchange -> {
            requests.add(exchange.getRequestURI().toString());
            assertEquals(COOKIE,exchange.getRequestHeaders().getFirst("Cookie"));
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
    }

    @Test void listingCoursesNeverReadsContentsTeachersOrAnnouncements() {
        json(API+"/users/me/courses",200,"{\"results\":[{\"courseId\":\"_course_1\"}]}");
        json(API+"/courses/_course_1",200,"{\"id\":\"_course_1\",\"courseId\":\"CSC1003\",\"name\":\"Programming\"}");
        doReturn(Map.of("id",course)).when(data).one("select id from courses where user_id=? and external_id=?",user,"_course_1");
        assertEquals(List.of(course),connector.courses(user));
        assertEquals(List.of(API+"/users/me/courses?limit=100",API+"/courses/_course_1"),requests);
        verifyNoInteractions(files);
    }

    @Test void directoryRequestDoesNotRecurseOrReadBodiesOrAttachments() {
        json(API+"/courses/_course_1/contents",200,"{\"results\":[{\"id\":\"_folder_1\",\"title\":\"Week 1\",\"hasChildren\":true,\"position\":0}]}");
        doReturn(Map.of("id",node)).when(data).one("select id from content_nodes where course_id=? and external_id=?",course,"_folder_1");
        assertEquals(List.of(node),connector.contents(user,course,null));
        assertEquals(List.of(API+"/courses/_course_1/contents?limit=100"),requests);
        verifyNoInteractions(files);
    }

    @Test void openingFolderReadsOnlyItsImmediateChildren() {
        doReturn(Map.of("externalId","_folder_1")).when(data).one("select external_id from content_nodes where id=? and course_id=?",node,course);
        json(API+"/courses/_course_1/contents/_folder_1/children",200,"{\"results\":[]}");
        assertEquals(List.of(),connector.contents(user,course,node));
        assertEquals(List.of(API+"/courses/_course_1/contents/_folder_1/children?limit=100"),requests);
    }

    @Test void bodyRequestDoesNotReadSiblingContentsOrAttachments() {
        json("/learn/api/v1/courses/_course_1/contents/_item_1",200,"{\"body\":{\"displayText\":\"<p>Lecture</p>\"}}");
        connector.node(user,node);
        assertEquals(List.of("/learn/api/v1/courses/_course_1/contents/_item_1"),requests);
        verifyNoInteractions(files);
    }

    @Test void attachmentListingStoresMetadataWithoutDownloadingBytes() {
        String path=API+"/courses/_course_1/contents/_item_1";
        json(path,200,"{\"contentHandler\":{\"id\":\"resource/x-bb-document\"}}");
        json(path+"/attachments",200,"{\"results\":[{\"id\":\"_file_1\",\"fileName\":\"lecture.pdf\",\"mimeType\":\"application/pdf\",\"size\":42}]}");
        when(files.remote(user,course,node,path+"/attachments/_file_1","lecture.pdf","application/pdf",42,"BLACKBOARD"))
            .thenReturn(Map.of("displayName","lecture.pdf"));
        assertEquals("lecture.pdf",connector.attachments(user,node).getFirst().get("displayName"));
        assertEquals(List.of(path,path+"/attachments?limit=100"),requests);
        verify(files).remote(user,course,node,path+"/attachments/_file_1","lecture.pdf","application/pdf",42,"BLACKBOARD");
        verifyNoMoreInteractions(files);
    }

    @Test void announcementsRequestNeverReadsOtherCourseResources() {
        json(API+"/courses/_course_1/announcements",200,"{\"results\":[]}");
        assertEquals(List.of(),connector.announcements(user,course));
        assertEquals(List.of(API+"/courses/_course_1/announcements?limit=100"),requests);
        verifyNoInteractions(files);
    }

    @Test void expiredTokenReturns401AndMarksConnection() {
        json(API+"/courses/_course_1/contents",401,"{}");
        var error=assertThrows(ResponseStatusException.class,()->connector.contents(user,course,null));
        assertEquals(HttpStatus.UNAUTHORIZED,error.getStatusCode());
        verify(data).update("update external_connections set status='LOGIN_REQUIRED' where user_id=? and provider='BLACKBOARD'",user);
    }

    @Test void expiredSessionAutomaticallySignsInAndRetriesOnlyTheRequestedDirectory() {
        when(login.automatic(user)).thenReturn(true);
        json(API+"/courses/_course_1/contents",401,"{}");
        when(login.login(user)).thenAnswer(invocation->{server.removeContext(API+"/courses/_course_1/contents");json(API+"/courses/_course_1/contents",200,"{\"results\":[]}");return Map.of();});
        assertEquals(List.of(),connector.contents(user,course,null));
        verify(login).login(user);
        assertEquals(2,requests.size());
        verifyNoInteractions(files);
    }

    @Test void foreignCourseIsRejectedBeforeAnyRemoteRequest() {
        UUID foreign=UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(data).one("select * from courses where id=? and user_id=?",foreign,user);
        assertThrows(ResponseStatusException.class,()->connector.contents(user,foreign,null));
        assertTrue(requests.isEmpty());
    }
}
