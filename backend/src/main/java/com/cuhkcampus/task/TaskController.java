package com.cuhkcampus.task;
import com.cuhkcampus.calendar.CalendarEvent;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.*;
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    final TaskService service;
    public TaskController(TaskService service){this.service=service;}
    @GetMapping public List<CampusTask> list(@RequestParam(required=false)UUID courseId,@RequestParam(required=false)String taskType,@RequestParam(required=false)String status,@RequestParam(required=false)Boolean starred,@RequestParam(required=false)Instant dueFrom,@RequestParam(required=false)Instant dueTo){return service.list(courseId,taskType,status,starred,dueFrom,dueTo);}
    @PostMapping public CampusTask create(@RequestBody Map<String,Object> body){return service.save(null,body);}
    @GetMapping("/{id}") public CampusTask get(@PathVariable UUID id){return service.get(id);}
    @PatchMapping("/{id}") public CampusTask update(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.save(id,body);}
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id){service.delete(id);}
    @PostMapping("/{id}/calendar-event") public CalendarEvent calendar(@PathVariable UUID id){return service.calendar(id);}
}
