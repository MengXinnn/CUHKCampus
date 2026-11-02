package com.cuhkcampus.mail;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/mail")
public class MailController {
    final MailService service;
    public MailController(MailService service){this.service=service;}
    @GetMapping public List<MailMessage> list(@RequestParam(required=false)String category,@RequestParam(required=false)UUID courseId,@RequestParam(required=false)Boolean isRead,@RequestParam(required=false)String q){return service.list(category,courseId,isRead,q);}
    @GetMapping("/{id}") public MailMessage get(@PathVariable UUID id){return service.get(id);}
    @PatchMapping("/{id}") public MailMessage patch(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.patch(id,body);}
}
