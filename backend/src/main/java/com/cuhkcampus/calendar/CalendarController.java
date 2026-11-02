package com.cuhkcampus.calendar;
import com.cuhkcampus.common.CurrentUser;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.*;
@RestController
@RequestMapping("/api/v1")
public class CalendarController {
    final CalendarService service;
    public CalendarController(CalendarService service){this.service=service;}
    @GetMapping("/calendar") public List<CalendarEvent> list(@RequestParam(required=false)Instant from,@RequestParam(required=false)Instant to){return service.list(from,to);}
    @PostMapping("/calendar/events") public CalendarEvent create(@RequestBody Map<String,Object> body){return service.save(null,body);}
    @PatchMapping("/calendar/events/{id}") public CalendarEvent patch(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.save(id,body);}
    @DeleteMapping("/calendar/events/{id}") public void delete(@PathVariable UUID id){service.delete(id);}
    @GetMapping("/courses/{id}/meeting-rules") public List<CourseMeetingRule> rules(@PathVariable UUID id){return service.rules(id);}
    @PostMapping("/courses/{id}/meeting-rules") public CourseMeetingRule rule(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.createRule(id,body);}
    @DeleteMapping("/meeting-rules/{id}") public void deleteRule(@PathVariable UUID id){service.deleteRule(id);}
    public record StudyPlan(List<UUID> taskIds,Instant from,Instant to){}
    @PostMapping("/calendar/study-plan") public List<CalendarEvent> study(@RequestBody StudyPlan input){return service.planStudySessions(CurrentUser.id(),input.taskIds(),input.from(),input.to());}
}
