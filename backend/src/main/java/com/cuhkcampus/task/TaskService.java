package com.cuhkcampus.task;
import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.course.CourseService;
import com.cuhkcampus.course.EntityFields;
import com.cuhkcampus.calendar.CalendarEvent;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.*;

@Service
@Transactional
public class TaskService {
    @PersistenceContext EntityManager em;
    final CourseService courses;
    public TaskService(CourseService courses){this.courses=courses;}
    public List<CampusTask> list(UUID courseId,String taskType,String status,Boolean starred,Instant dueFrom,Instant dueTo){return em.createQuery("from CampusTask t where t.userId=:user order by t.dueAt asc nulls last,t.title",CampusTask.class).setParameter("user",CurrentUser.id()).getResultList().stream()
        .filter(t->courseId==null || courseId.equals(t.courseId)).filter(t->taskType==null || taskType.equals(t.taskType)).filter(t->status==null || status.equals(t.status))
        .filter(t->starred==null || starred==t.starred).filter(t->dueFrom==null || (t.dueAt!=null && !t.dueAt.isBefore(dueFrom))).filter(t->dueTo==null || (t.dueAt!=null && t.dueAt.isBefore(dueTo))).toList();}
    public CampusTask get(UUID id){var t=em.find(CampusTask.class,id);if(t==null || !t.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return t;}
    public CampusTask save(UUID id,Map<String,Object> input){var t=id==null?new CampusTask():get(id);t.userId=CurrentUser.id();if(input.get("courseId")!=null)courses.get(UUID.fromString(input.get("courseId").toString()));EntityFields.apply(t,input,"courseId","taskType","title","description","status","priority","dueAt","estimatedMinutes","organizer","category","startsAt","endsAt","location","registrationUrl","starred");if(id==null)em.persist(t);return t;}
    public void delete(UUID id){em.remove(get(id));}
    public CalendarEvent calendar(UUID id){
        var t=get(id);
        var linked=em.createQuery("from CalendarEvent e where e.taskId=:task and e.userId=:user order by e.startsAt",CalendarEvent.class).setParameter("task",id).setParameter("user",CurrentUser.id()).getResultList();
        var e=linked.stream().filter(item->!"STUDY".equals(item.eventType)).findFirst().orElseGet(CalendarEvent::new);
        boolean create=e.userId==null;
        e.userId=t.userId;e.taskId=t.id;e.courseId=t.courseId;e.title=t.title;e.location=t.location;e.eventType=t.taskType.equals("ACTIVITY")?"ACTIVITY":"PERSONAL";
        e.startsAt=t.startsAt==null?t.dueAt:t.startsAt;
        if(e.startsAt==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先设置任务时间");
        e.endsAt=t.endsAt==null?e.startsAt.plusSeconds(3600):t.endsAt;
        if(!e.endsAt.isAfter(e.startsAt))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"结束时间应晚于开始时间");
        if(create)em.persist(e);return e;
    }
}
