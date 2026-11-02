package com.cuhkcampus.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** ChatGPT plan usage uses the public Responses protocol, independently of API-key chat completions. */
public class ChatGptResponsesModel implements ChatModel {
    private final Supplier<String> token;
    private final String model;
    private final URI endpoint;
    private final HttpClient http=HttpClient.newHttpClient();
    private final ObjectMapper json=new ObjectMapper();
    public ChatGptResponsesModel(Supplier<String> token,String model) {
        this(token,model,URI.create("https://api.openai.com/v1/responses"));
    }
    ChatGptResponsesModel(Supplier<String> token,String model,URI endpoint) {this.token=token;this.model=model;this.endpoint=endpoint;}
    public static class InferenceException extends RuntimeException {
        public InferenceException(String message) {super(message);}
    }
    @Override public ChatOptions getOptions() {return ToolCallingChatOptions.builder().model(model).build();}
    @Override public ChatResponse call(Prompt prompt) {
        StringBuilder content=new StringBuilder();
        stream(prompt).doOnNext(response->content.append(response.getResult().getOutput().getText())).blockLast();
        return response(content.toString());
    }
    @Override public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.<ChatResponse>create(sink->{
            try {
                var input=input(prompt);
                var options=prompt.getOptions();
                var callbacks=options instanceof ToolCallingChatOptions tools && tools.getToolCallbacks()!=null?tools.getToolCallbacks():List.<ToolCallback>of();
                var context=options instanceof ToolCallingChatOptions tools && tools.getToolContext()!=null?tools.getToolContext():Map.<String,Object>of();
                List<Map<String,Object>> definitions=new ArrayList<>();
                for(var callback:callbacks) {
                    var definition=callback.getToolDefinition();
                    definitions.add(Map.of("type","function","name",definition.name(),"description",definition.description(),"parameters",json.readValue(definition.inputSchema(),Map.class),"strict",false));
                }
                boolean toolsPending;
                boolean hasText=false;
                do {
                    var request=new LinkedHashMap<String,Object>();
                    request.put("model",model);request.put("input",input);request.put("store",false);request.put("stream",true);
                    request.put("include",List.of("reasoning.encrypted_content"));
                    if(!definitions.isEmpty()) request.put("tools",definitions);
                    var httpRequest=HttpRequest.newBuilder(endpoint).header("Authorization","Bearer "+token.get()).header("Content-Type","application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
                    var httpResponse=http.send(httpRequest,HttpResponse.BodyHandlers.ofInputStream());
                    try(var stream=httpResponse.body()) {
                        if(httpResponse.statusCode()!=200) throw new InferenceException("ChatGPT 请求失败（HTTP "+httpResponse.statusCode()+"）");
                        var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));
                        JsonNode completed=null;
                        var outputItems=new TreeMap<Integer,JsonNode>();
                        StringBuilder eventData=new StringBuilder();String line;
                        while(!sink.isCancelled() && (line=reader.readLine())!=null) {
                            if(line.startsWith("data:")) {
                                if(!eventData.isEmpty()) eventData.append('\n');
                                eventData.append(line.substring(5).stripLeading());
                            } else if(line.isEmpty() && !eventData.isEmpty()) {
                                JsonNode event=json.readTree(eventData.toString());eventData.setLength(0);
                                String type=event.path("type").asText();
                                if("response.output_text.delta".equals(type)) {
                                    String delta=event.path("delta").asText();
                                    if(!delta.isBlank()) hasText=true;
                                    sink.next(response(delta));
                                }
                                else if("response.output_item.done".equals(type)) outputItems.put(event.path("output_index").asInt(),event.get("item"));
                                else if("response.completed".equals(type)) {
                                    completed=event.get("response");
                                    int index=0;
                                    for(var item:completed.path("output")) outputItems.put(index++,item);
                                    break;
                                }
                                else if("response.failed".equals(type)) throw new InferenceException("ChatGPT 推理失败："+event.path("response").path("error").path("code").asText());
                                else if("response.incomplete".equals(type)) throw new InferenceException("ChatGPT 回答未完成："+event.path("response").path("incomplete_details").path("reason").asText());
                                else if("error".equals(type)) throw new InferenceException("ChatGPT 请求错误："+event.path("code").asText());
                            }
                        }
                        if(sink.isCancelled()) return;
                        if(completed==null) throw new InferenceException("ChatGPT 连接中断，未收到完成事件");
                        var output=outputItems.values();
                        toolsPending=false;
                        for(JsonNode item:output) input.add(json.convertValue(item,Map.class));
                        for(JsonNode item:output) {
                            if(!"function_call".equals(item.path("type").asText())) continue;
                            toolsPending=true;
                            String name=item.path("name").asText();
                            ToolCallback callback=callbacks.stream().filter(c->c.getToolDefinition().name().equals(name)).findFirst()
                                    .orElseThrow(()->new InferenceException("模型调用了未提供的工具："+name));
                            String result;
                            try {result=callback.call(item.path("arguments").asText(),new ToolContext(context));}
                            catch(org.springframework.ai.tool.execution.ToolExecutionException error) {throw new InferenceException(AiFactory.toolError(error));}
                            input.add(Map.of("type","function_call_output","call_id",item.path("call_id").asText(),"output",result));
                        }
                    }
                } while(toolsPending && !sink.isCancelled());
                if(!hasText) throw new InferenceException("模型没有返回回答");
                sink.complete();
            } catch(Exception e) {sink.error(e);}
        // HTTP reading occupies this worker; demand must not queue behind that read loop.
        }).subscribeOn(Schedulers.boundedElastic(), false);
    }
    private List<Map<String,Object>> input(Prompt prompt) {
        var input=new ArrayList<Map<String,Object>>();
        for(Message message:prompt.getInstructions()) {
            if(message instanceof ToolResponseMessage tools) {
                for(var tool:tools.getResponses()) input.add(Map.of("type","function_call_output","call_id",tool.id(),"output",tool.responseData()));
            } else if(message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                if(assistant.getText()!=null && !assistant.getText().isBlank()) input.add(Map.of("role","assistant","content",assistant.getText()));
                for(var call:assistant.getToolCalls()) input.add(Map.of("type","function_call","call_id",call.id(),"name",call.name(),"arguments",call.arguments()));
            } else if(message instanceof UserMessage user && !user.getMedia().isEmpty()) {
                var content=new ArrayList<Map<String,Object>>();
                content.add(Map.of("type","input_text","text",user.getText()));
                for(var media:user.getMedia()) content.add(Map.of("type","input_image","image_url",
                    "data:"+media.getMimeType()+";base64,"+Base64.getEncoder().encodeToString(media.getDataAsByteArray())));
                input.add(Map.of("role","user","content",content));
            } else input.add(Map.of("role",message.getMessageType().getValue(),"content",message.getText()));
        }
        return input;
    }
    private ChatResponse response(String text) {return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));}
}
