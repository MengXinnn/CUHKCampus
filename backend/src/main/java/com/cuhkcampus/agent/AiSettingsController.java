package com.cuhkcampus.agent;

import com.cuhkcampus.common.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/ai")
public class AiSettingsController {
    private final AiSettingsService service;
    public AiSettingsController(AiSettingsService service) {this.service=service;}
    public record Save(String kind,String mode,String baseUrl,String model,String apiKey) {}
    public record Request(String kind,String baseUrl,String model,String apiKey) {}
    @GetMapping("/config") public Map<String,Object> config() {return service.all(CurrentUser.id());}
    @PostMapping("/config") public AiSettingsService.Config save(@RequestBody Save body) {return service.save(CurrentUser.id(),body.kind(),body.mode(),body.baseUrl(),body.model(),body.apiKey());}
    @PostMapping("/models") public Map<String,Object> models(@RequestBody Request body) {return Map.of("models",service.models(CurrentUser.id(),body.kind(),body.baseUrl(),body.apiKey()));}
    @PostMapping("/test") public Map<String,Object> test(@RequestBody Request body) {return service.test(CurrentUser.id(),body.kind(),body.baseUrl(),body.model(),body.apiKey());}
}
