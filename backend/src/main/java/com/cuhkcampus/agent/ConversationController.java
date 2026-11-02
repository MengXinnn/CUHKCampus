package com.cuhkcampus.agent;

import com.cuhkcampus.common.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final AgentService service;
    public ConversationController(AgentService service) {this.service=service;}
    public record Title(String title) {}
    public record Send(String content,List<Map<String,Object>> contextRefs) {}
    @GetMapping public Map<String,Object> list(@RequestParam(required=false) String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size) {return service.list(CurrentUser.id(),q,page,size);}
    @PostMapping public Map<String,Object> create(@RequestBody Title body) {return service.create(CurrentUser.id(),body.title());}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id) {return service.get(CurrentUser.id(),id);}
    @PatchMapping("/{id}") public Map<String,Object> rename(@PathVariable UUID id,@RequestBody Title body) {return service.rename(CurrentUser.id(),id,body.title());}
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id) {service.delete(CurrentUser.id(),id);}
    @GetMapping("/{id}/messages") public Map<String,Object> messages(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="100") int size) {return service.messages(CurrentUser.id(),id,page,size);}
    @PostMapping(value="/{id}/messages",produces="text/event-stream") public SseEmitter send(@PathVariable UUID id,@RequestBody Send body) {return service.send(CurrentUser.id(),id,body.content(),body.contextRefs());}
}
