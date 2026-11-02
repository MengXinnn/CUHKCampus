package com.cuhkcampus.integration.chatgpt;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatGptOAuthServiceTest {
    @TempDir Path directory;
    @Test void hostIdentityPersistsAcrossServiceInstances()throws Exception {
        Path file=directory.resolve("data/chatgpt-host-id");
        var first=new ChatGptOAuthService(null,null,file.toString());
        String id=first.hostId();assertTrue(id.startsWith("urn:uuid:"));
        assertEquals(id,Files.readString(file));
        assertEquals(id,new ChatGptOAuthService(null,null,file.toString()).hostId());
        assertEquals("rw-------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
    @Test void callbackWithoutMatchingAuthorizationAttemptIsRejected(){
        var service=new ChatGptOAuthService(null,null,directory.resolve("host-id").toString());
        var error=assertThrows(ResponseStatusException.class,()->service.callback("unbound-state","code","oaiapp_test",null));
        assertEquals(400,error.getStatusCode().value());
    }
    @Test void identityLoginDoesNotEnablePlanUsage(){
        UUID user=UUID.randomUUID();var data=mock(AgentData.class);var cipher=mock(CredentialCipher.class);
        Map<String,Object> record=new LinkedHashMap<>();record.put("access_token","test-token");record.put("email","student@example.test");record.put("scopes",List.of("openid","profile","email"));
        when(data.rows(anyString(),eq(user))).thenReturn(List.of(Map.of("credentials",Map.of("encrypted","ciphertext"))));
        when(cipher.decrypt("ciphertext")).thenReturn("record");when(data.object("record")).thenReturn(record);
        var service=new ChatGptOAuthService(data,cipher,directory.resolve("host-id").toString());
        var status=service.status(user);assertEquals(true,status.get("connected"));assertEquals(false,status.get("planEnabled"));assertFalse(status.containsKey("access_token"));
        var error=assertThrows(ResponseStatusException.class,()->service.accessToken(user));assertEquals(403,error.getStatusCode().value());
    }
}
