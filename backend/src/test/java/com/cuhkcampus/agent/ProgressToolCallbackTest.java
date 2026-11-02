package com.cuhkcampus.agent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ProgressToolCallbackTest {
    private ToolCallback tool() {
        var tool=mock(ToolCallback.class);
        when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder().name("list_courses").description("courses").inputSchema("{}").build());
        return tool;
    }
    @Test void emitsRunningBeforeExecutionAndCompletionWithSameId() {
        var events=new ArrayList<Map<String,Object>>();var tool=tool();
        when(tool.call(anyString(),any(ToolContext.class))).thenAnswer(invocation->{assertEquals("RUNNING",events.getFirst().get("status"));return "courses";});
        assertEquals("courses",new ProgressToolCallback(tool,events::add).call("{}"));
        assertEquals(List.of("RUNNING","SUCCEEDED"),events.stream().map(e->e.get("status")).toList());
        assertEquals(events.getFirst().get("id"),events.getLast().get("id"));
    }
    @Test void emitsFailureAndStillPropagatesOriginalException() {
        var events=new ArrayList<Map<String,Object>>();var tool=tool();var failure=new IllegalStateException("BB failed");
        when(tool.call(anyString(),any(ToolContext.class))).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->new ProgressToolCallback(tool,events::add).call("{}")));
        assertEquals(List.of("RUNNING","FAILED"),events.stream().map(e->e.get("status")).toList());
    }
}
