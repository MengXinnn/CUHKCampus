package com.cuhkcampus.integration.outlook;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import com.cuhkcampus.integration.AttachmentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OutlookSyncServiceTest {
    private static final String GRAPH="https://graph.microsoft.com/v1.0";
    private static final String REDIRECT="http://localhost:8080/api/v1/connections/outlook/callback";
    private final UUID user=UUID.randomUUID();
    private JdbcClient jdbc;
    private AgentData data;
    private CredentialCipher cipher;
    private MockRestServiceServer graph;
    private OutlookSyncService service;
    private final ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup(){
        jdbc=mock(JdbcClient.class);
        data=spy(new AgentData(jdbc));
        cipher=mock(CredentialCipher.class);
        RestClient.Builder builder=RestClient.builder();
        graph=MockRestServiceServer.bindTo(builder).build();
        service=new OutlookSyncService(data,cipher,mock(AttachmentStore.class),"server-client-id",REDIRECT,builder);
    }

    @SuppressWarnings("unchecked")
    private void queryString(String sql,String value){
        var statement=mock(JdbcClient.StatementSpec.class);
        JdbcClient.MappedQuerySpec<String> query=mock(JdbcClient.MappedQuerySpec.class);
        when(jdbc.sql(sql)).thenReturn(statement);
        when(statement.param(user)).thenReturn(statement);
        when(statement.query(String.class)).thenReturn(query);
        when(query.single()).thenReturn(value);
    }

    private void connection(){
        doReturn(Map.of("id",UUID.randomUUID(),"credentials",Map.of("encrypted","fixture-ciphertext")))
            .when(data).one("select * from external_connections where user_id=? and provider='OUTLOOK'",user);
        when(cipher.decrypt("fixture-ciphertext")).thenReturn("{\"access_token\":\"fixture-access-token\",\"expires_at\":\""+Instant.now().plusSeconds(3600)+"\",\"client_id\":\"personal-client-id\"}");
    }

    private Map<String,String> parameters(URI uri){
        var result=new LinkedHashMap<String,String>();
        for(String pair:uri.getRawQuery().split("&")){
            String[] values=pair.split("=",2);
            result.put(URLDecoder.decode(values[0],StandardCharsets.UTF_8),URLDecoder.decode(values[1],StandardCharsets.UTF_8));
        }
        return result;
    }

    @Test void authorizationUsesServerClientIgnoringUserPreferenceAndOnlyDelegatedPermissions(){
        queryString("select preferences::text from users where id=?","{\"outlookClientId\":\"personal-client-id\"}");
        var session=new MockHttpSession();
        URI url=URI.create(service.authorize(user,session));
        var params=parameters(url);
        assertEquals("login.microsoftonline.com",url.getHost());
        assertEquals("server-client-id",params.get("client_id"));
        assertEquals(REDIRECT,params.get("redirect_uri"));
        assertEquals("code",params.get("response_type"));
        assertEquals("S256",params.get("code_challenge_method"));
        assertEquals(Set.of("offline_access","User.Read","Mail.Read","Calendars.ReadWrite"),Set.copyOf(Arrays.asList(params.get("scope").split(" "))));
        assertFalse(params.get("scope").contains(".default"));
        assertEquals(params.get("state"),session.getAttribute("outlookState"));
        assertEquals(user,session.getAttribute("outlookUser"));
        assertEquals("server-client-id",session.getAttribute("outlookClientId"));
        graph.verify();
    }

    @Test void missingClientConfigurationReturnsActionableBadRequestBeforeAnyMicrosoftRequest(){
        queryString("select preferences::text from users where id=?","{}");
        RestClient.Builder builder=RestClient.builder();
        var noRequests=MockRestServiceServer.bindTo(builder).build();
        var unconfigured=new OutlookSyncService(data,cipher,mock(AttachmentStore.class),"",REDIRECT,builder);
        var error=assertThrows(ResponseStatusException.class,()->unconfigured.authorize(user,new MockHttpSession()));
        assertEquals(400,error.getStatusCode().value());
        assertTrue(error.getReason().contains("服务端"));
        noRequests.verify();
    }

    @Test void calendarCreationUsesCurrentUsersEventAndTimezoneWithoutSendingInvitations()throws Exception{
        UUID event=UUID.randomUUID();
        doReturn(Map.of("id",event,"title","学习安排","startsAt","2026-10-30T01:00:00Z","endsAt","2026-10-30T02:00:00Z","allDay",false,"location","图书馆"))
            .when(data).one("select * from calendar_events where id=? and user_id=?",event,user);
        queryString("select timezone from users where id=?","Asia/Shanghai");
        connection();
        graph.expect(requestTo(GRAPH+"/me/events")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization","Bearer fixture-access-token"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(request->{
                var payload=json.readTree(((MockClientHttpRequest)request).getBodyAsString());
                assertEquals("学习安排",payload.path("subject").asText());
                assertEquals("2026-10-30T09:00",payload.path("start").path("dateTime").asText());
                assertEquals("2026-10-30T10:00",payload.path("end").path("dateTime").asText());
                assertEquals("Asia/Shanghai",payload.path("start").path("timeZone").asText());
                assertEquals("Asia/Shanghai",payload.path("end").path("timeZone").asText());
                assertEquals("图书馆",payload.path("location").path("displayName").asText());
                assertFalse(payload.path("isAllDay").asBoolean());
                assertFalse(payload.has("attendees"),"Creating a personal event must not email meeting invitations");
            })
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"graph-event-id\",\"webLink\":\"https://outlook.office.com/calendar/item/verification\"}"));
        var result=service.createCalendarEvent(user,event);
        assertEquals("graph-event-id",result.get("id"));
        verify(data).one("select * from calendar_events where id=? and user_id=?",event,user);
        verify(data).one("select * from external_connections where user_id=? and provider='OUTLOOK'",user);
        graph.verify();
    }

    @Test void anotherUsersCalendarEventCannotReachGraph(){
        UUID event=UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(data)
            .one("select * from calendar_events where id=? and user_id=?",event,user);
        var error=assertThrows(ResponseStatusException.class,()->service.createCalendarEvent(user,event));
        assertEquals(404,error.getStatusCode().value());
        verifyNoInteractions(cipher);
        graph.verify();
    }

    @Test void liveMailboxSearchEncodesUnicodeQueryAndUsesSignedInUsersGraphEndpoint(){
        connection();
        graph.expect(requestTo(GRAPH+"/me/mailFolders?$top=100")).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization","Bearer fixture-access-token"))
            .andRespond(withSuccess("{\"value\":[]}",MediaType.APPLICATION_JSON));
        graph.expect(request->{
                assertEquals("/v1.0/me/messages",request.getURI().getPath());
                var query=parameters(request.getURI());
                assertEquals("\"期中 考试\"",query.get("$search"));
                assertEquals("10",query.get("$top"));
                assertTrue(query.get("$select").contains("parentFolderId"));
            }).andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization","Bearer fixture-access-token"))
            .andRespond(withSuccess("{\"value\":[]}",MediaType.APPLICATION_JSON));
        assertTrue(service.search(user,"期中 考试",10).isEmpty());
        graph.verify();
    }
}
