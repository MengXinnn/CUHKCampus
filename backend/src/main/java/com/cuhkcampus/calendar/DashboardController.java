package com.cuhkcampus.calendar;
import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.course.CourseService;
import com.cuhkcampus.mail.MailService;
import com.cuhkcampus.task.TaskService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.time.*;
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    final CalendarService calendar;final CourseService courses;final MailService mail;final TaskService tasks;
    public DashboardController(CalendarService calendar,CourseService courses,MailService mail,TaskService tasks){this.calendar=calendar;this.courses=courses;this.mail=mail;this.tasks=tasks;}
    @GetMapping public Map<String,Object> get(@RequestParam(required=false)LocalDate date){var zone=calendar.timezone(CurrentUser.id());var day=date==null?LocalDate.now(zone):date;var from=day.atStartOfDay(zone).toInstant();return Map.of("courses",courses.list(null,null),"tasks",tasks.list(null,null,"TODO",null,null,from.plusSeconds(7*86400)),"events",calendar.list(from,day.plusDays(1).atStartOfDay(zone).toInstant()),"mail",mail.list(null,null,false,null).stream().limit(5).toList(),"announcements",courses.announcements(null).stream().limit(5).toList());}
}
