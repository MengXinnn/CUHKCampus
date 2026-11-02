package com.cuhkcampus.agent;

import org.springframework.ai.chat.client.ChatClient;
import com.cuhkcampus.integration.chatgpt.ChatGptOAuthService;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Component
public class AiFactory {
    private final ChatGptOAuthService chatgpt;
    private final AiSettingsService settings;
    public AiFactory(ChatGptOAuthService chatgpt, AiSettingsService settings) {
        this.chatgpt=chatgpt;this.settings=settings;
    }
    public ChatClient client(UUID userId) {
        return configured(userId,"agent","Agent");
    }
    public ChatClient translationClient(UUID userId) {
        return configured(userId,"translation","文档翻译");
    }
    static String toolError(org.springframework.ai.tool.execution.ToolExecutionException error) {
        Throwable cause=error;
        while(cause.getCause()!=null) cause=cause.getCause();
        String reason=cause instanceof ResponseStatusException status ? status.getReason() : cause.getMessage();
        return "工具 "+error.getToolDefinition().name()+" 执行失败："+reason;
    }
    private ChatClient configured(UUID userId,String kind,String name) {
        AiSettingsService.Config config=settings.config(userId,kind);
        String mode=config.mode();
        String selectedModel=config.model();
        if("CHATGPT".equals(mode)) {
            if(selectedModel.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先选择"+name+"使用的 ChatGPT 模型");
            return ChatClient.builder(new ChatGptResponsesModel(()->chatgpt.accessToken(userId),selectedModel)).build();
        }
        if(!"API_KEY".equals(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"未知 AI 模式");
        String endpoint=config.baseUrl();
        if(endpoint.isBlank() || selectedModel.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请在设置中填写"+name+"的模型服务地址与模型名称");
        var chatModel=OpenAiChatModel.builder().toolCallingManager(org.springframework.ai.model.tool.ToolCallingManager.builder()
                .toolExecutionExceptionProcessor(error -> {throw new ChatGptResponsesModel.InferenceException(toolError(error));}).build()).options(OpenAiChatOptions.builder()
                .baseUrl(endpoint).apiKey(settings.apiKey(userId,kind)).model(selectedModel).maxRetries(0).build()).build();
        return ChatClient.builder(chatModel).build();
    }
}
