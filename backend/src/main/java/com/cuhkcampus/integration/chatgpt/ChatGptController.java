package com.cuhkcampus.integration.chatgpt;

import com.cuhkcampus.common.CurrentUser;
import org.springframework.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/connections/chatgpt")
public class ChatGptController {
    private final ChatGptOAuthService service;
    private final String frontendUrl;
    public ChatGptController(ChatGptOAuthService service,@Value("${campus.frontend-url:http://localhost:8080}") String frontendUrl){this.service=service;this.frontendUrl=frontendUrl;}
    @GetMapping("/authorize") public Map<String,String> authorize(){return Map.of("url",service.authorize(CurrentUser.id()));}
    @GetMapping("/callback") public ResponseEntity<Void> callback(@RequestParam String state,@RequestParam(required=false)String code,@RequestParam(name="client_id",required=false)String clientId,@RequestParam(required=false)String error){service.callback(state,code,clientId,error);return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(frontendUrl+"/settings")).build();}
    @GetMapping("/status") public Map<String,Object> status(){return service.status(CurrentUser.id());}
    @GetMapping("/models") public Map<String,Object> models(){return service.models(CurrentUser.id());}
}
