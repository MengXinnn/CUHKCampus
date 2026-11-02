package com.cuhkcampus.integration;

import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.integration.blackboard.BlackboardLoginService;
import com.cuhkcampus.integration.outlook.OutlookSyncService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;
import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {
    private final ConnectionService service;
    private final BlackboardLoginService blackboardLogin;
    private final OutlookSyncService outlook;
    private final String frontendUrl;
    public ConnectionController(ConnectionService service,BlackboardLoginService blackboardLogin,OutlookSyncService outlook,@Value("${campus.frontend-url:http://localhost:8080}") String frontendUrl) {this.service=service;this.blackboardLogin=blackboardLogin;this.outlook=outlook;this.frontendUrl=frontendUrl;}
    public record SelectFolders(List<UUID> folderIds) {}
    @GetMapping public List<Map<String,Object>> list() {return service.list(CurrentUser.id());}
    public record BlackboardAccount(String username,String password) {}
    @GetMapping("/blackboard/account") public Map<String,Object> account(){return blackboardLogin.account(CurrentUser.id());}
    @PostMapping("/blackboard/account") public Map<String,Object> account(@RequestBody BlackboardAccount body){return blackboardLogin.configure(CurrentUser.id(),body.username(),body.password());}
    @PostMapping("/blackboard/automatic") public Map<String,Object> automatic(){return blackboardLogin.login(CurrentUser.id());}
    @GetMapping("/outlook/authorize") public Map<String,String> authorize(HttpSession session) {return Map.of("url",outlook.authorize(CurrentUser.id(),session));}
    @GetMapping("/outlook/callback") public ResponseEntity<Void> callback(@RequestParam(required=false)String code,@RequestParam(required=false)String state,@RequestParam(required=false)String error,@RequestParam(name="error_description",required=false)String errorDescription,HttpSession session) {
        if(error!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Outlook 授权未完成："+(errorDescription==null?error:errorDescription));
        outlook.callback(code,state,session);return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl+"/settings")).build();
    }
    @GetMapping("/outlook/folders") public List<Map<String,Object>> folders() {return outlook.folders(CurrentUser.id());}
    @PatchMapping("/outlook/folders") public List<Map<String,Object>> select(@RequestBody SelectFolders body) {return outlook.select(CurrentUser.id(),body.folderIds());}
    @PostMapping(value="/outlook/sync",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter sync() {
        UUID userId=CurrentUser.id();
        var emitter=new SseEmitter(0L);
        Thread.ofVirtual().start(() -> {
            try {
                var result=service.syncOutlook(userId,progress ->
                    send(emitter,SseEmitter.event().name("progress").data(progress)));
                send(emitter,SseEmitter.event().name("done").data(result));
                emitter.complete();
            } catch(Exception error) {
                String message=error instanceof ResponseStatusException status&&status.getReason()!=null
                    ?status.getReason():error.getMessage();
                try {
                    send(emitter,SseEmitter.event().name("error").data(Map.of("message",message)));
                    emitter.complete();
                } catch(Exception disconnected) {emitter.completeWithError(error);}
            }
        });
        return emitter;
    }
    @DeleteMapping("/{provider}") public void disconnect(@PathVariable String provider) {service.disconnect(CurrentUser.id(),provider);}
    private void send(SseEmitter emitter,SseEmitter.SseEventBuilder event) {
        try {emitter.send(event);}
        catch(IOException error) {throw new UncheckedIOException(error);}
    }
}
