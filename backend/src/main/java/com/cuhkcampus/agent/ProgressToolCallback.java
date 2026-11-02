package com.cuhkcampus.agent;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

class ProgressToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final Consumer<Map<String,Object>> progress;
    ProgressToolCallback(ToolCallback delegate, Consumer<Map<String,Object>> progress) {
        this.delegate=delegate;this.progress=progress;
    }
    public ToolDefinition getToolDefinition(){return delegate.getToolDefinition();}
    public ToolMetadata getToolMetadata(){return delegate.getToolMetadata();}
    public String call(String input){return call(input,new ToolContext(Map.of()));}
    public String call(String input,ToolContext context) {
        String id=UUID.randomUUID().toString();
        emit(id,"RUNNING");
        try {
            String result=delegate.call(input,context);
            emit(id,"SUCCEEDED");
            return result;
        } catch(RuntimeException error) {emit(id,"FAILED");throw error;}
    }
    private void emit(String id,String status){progress.accept(Map.of("id",id,"name",getToolDefinition().name(),"status",status));}
}
