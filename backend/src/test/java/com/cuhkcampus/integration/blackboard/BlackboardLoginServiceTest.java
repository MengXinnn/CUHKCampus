package com.cuhkcampus.integration.blackboard;
import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.integration.BrowserAccounts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BlackboardLoginServiceTest {
    @Test void accountStatusDoesNotExposePasswordAndExistingPasswordCanBeReused(){
        var accounts=mock(BrowserAccounts.class);
        UUID user=UUID.randomUUID();
        when(accounts.get(user,"BLACKBOARD")).thenReturn(Map.of("username","test@example.edu","password","private-password"));
        var service=spy(new BlackboardLoginService(mock(BlackboardApiConnector.class),mock(AgentData.class),accounts,"https://bb.cuhk.edu.cn","chrome"));
        doReturn(Map.of("status","CONNECTED")).when(service).login(user);
        assertEquals(Map.of("username","test@example.edu","passwordSaved",true),service.account(user));
        service.configure(user,"test@example.edu","");
        verify(accounts).save(user,"BLACKBOARD",Map.of("username","test@example.edu","password","private-password"));
    }
}
