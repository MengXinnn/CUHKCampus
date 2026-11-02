package com.cuhkcampus.agent;

import com.cuhkcampus.calendar.CalendarService;
import com.cuhkcampus.document.DocumentService;
import com.cuhkcampus.integration.ConnectionService;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class AgentServiceTest {
    @RestController static class Endpoint {
        final AgentService service;
        Endpoint(AgentService service){this.service=service;}
        @GetMapping(value="/test",produces="text/event-stream") SseEmitter send(){return service.send(UUID.randomUUID(),UUID.randomUUID(),"hello",List.of());}
    }
    private String response(Flux<ChatResponse> stream) throws Exception {
        var data=spy(new AgentData(mock(JdbcClient.class)));
        doNothing().when(data).conversation(any(),any());
        doReturn(1).when(data).update(anyString(),any(Object[].class));
        doReturn(List.of(Map.of("role","USER","content","hello","contextRefs",List.of()))).when(data).rows(anyString(),any(Object[].class));
        var model=mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(stream);
        var ai=mock(AiFactory.class);
        when(ai.client(any())).thenReturn(ChatClient.builder(model).build());
        var service=new AgentService(data,ai,mock(DocumentService.class),mock(CalendarService.class),mock(ConnectionService.class),mock(BlackboardApiConnector.class));
        var mvc=MockMvcBuilders.standaloneSetup(new Endpoint(service)).build();
        var result=mvc.perform(get("/test")).andReturn();
        result.getAsyncResult(3000);
        String body=mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("event:error"),body);
        assertFalse(body.contains("event:done"),body);
        return body;
    }
    @Test void emptyStreamBecomesVisibleErrorInsteadOfSuccessfulEmptyAnswer() throws Exception {
        assertTrue(response(Flux.empty()).contains("模型没有返回回答"));
    }
    @Test void nestedToolFailureReachesSseWithNameAndReason() throws Exception {
        var tool=ToolDefinition.builder().name("list_courses").description("courses").inputSchema("{}").build();
        var error=new RuntimeException(new ToolExecutionException(tool,new IllegalStateException("BB HTTP 403")));
        String body=response(Flux.error(error));
        assertTrue(body.contains("list_courses"),body);
        assertTrue(body.contains("BB HTTP 403"),body);
    }
}
