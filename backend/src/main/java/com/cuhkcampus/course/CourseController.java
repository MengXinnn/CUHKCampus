package com.cuhkcampus.course;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class CourseController {
    private final CourseService service;
    public CourseController(CourseService service){this.service=service;}
    @GetMapping("/courses") public List<Course> list(@RequestParam(required=false) String term,@RequestParam(required=false) String query){return service.list(term,query);}
    @PostMapping("/courses") public Course create(@RequestBody Map<String,Object> body){return service.save(null,body);}
    @GetMapping("/courses/{id}") public Course get(@PathVariable UUID id){return service.detail(id);}
    @PatchMapping("/courses/{id}") public Course update(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.save(id,body);}
    @GetMapping("/courses/{id}/content") public List<ContentNode> content(@PathVariable UUID id,@RequestParam(required=false) UUID parentId){return service.content(id,parentId);}
    @GetMapping("/content/{id}") public ContentNode node(@PathVariable UUID id){return service.node(id);}
    @GetMapping("/content/{id}/documents") public List<Map<String,Object>> attachments(@PathVariable UUID id){return service.attachments(id);}
    @GetMapping("/announcements") public List<Announcement> announcements(@RequestParam UUID courseId){return service.announcements(courseId);}
    @GetMapping("/announcements/{id}") public Announcement announcement(@PathVariable UUID id){return service.announcement(id);}
    @PatchMapping("/announcements/{id}") public Announcement mark(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.markAnnouncement(id,body);}
    @GetMapping("/courses/{id}/notes") public List<CourseNote> notes(@PathVariable UUID id){return service.notes(id);}
    @PostMapping("/courses/{id}/notes") public CourseNote createNote(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.saveNote(id,null,body);}
    @PatchMapping("/notes/{id}") public CourseNote updateNote(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.saveNote(null,id,body);}
    @DeleteMapping("/notes/{id}") public void deleteNote(@PathVariable UUID id){service.deleteNote(id);}
}
