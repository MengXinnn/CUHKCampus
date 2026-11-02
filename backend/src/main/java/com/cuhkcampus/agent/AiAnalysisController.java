package com.cuhkcampus.agent;

import com.cuhkcampus.common.CurrentUser;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class AiAnalysisController {
    private final AiAnalysisService service;
    public AiAnalysisController(AiAnalysisService service) {this.service=service;}
    @PostMapping("/mail/{id}/extract") public Map<String,Object> extract(@PathVariable UUID id) {return service.extractMail(CurrentUser.id(),id);}
    @GetMapping("/briefings") public Map<String,Object> get(@RequestParam(required=false) LocalDate date) {return service.briefing(CurrentUser.id(),date==null?LocalDate.now(ZoneId.of("Asia/Shanghai")):date,false);}
    @PostMapping("/briefings") public Map<String,Object> generate(@RequestParam(required=false) LocalDate date) {return service.briefing(CurrentUser.id(),date==null?LocalDate.now(ZoneId.of("Asia/Shanghai")):date,true);}
}
