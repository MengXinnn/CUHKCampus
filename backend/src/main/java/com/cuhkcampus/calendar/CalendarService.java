package com.cuhkcampus.calendar;

import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.course.*;
import com.cuhkcampus.task.CampusTask;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.time.*;
import java.util.*;

@Service
@Transactional
public class CalendarService {
    @PersistenceContext EntityManager em;
    final CourseService courses;
    final JdbcClient jdbc;
    public CalendarService(CourseService courses,JdbcClient jdbc){this.courses=courses;this.jdbc=jdbc;}
    public ZoneId timezone(UUID userId){return ZoneId.of(jdbc.sql("select timezone from users where id=:id").param("id",userId).query(String.class).single());}
    public List<CalendarEvent> events(UUID userId,Instant from,Instant to){return em.createQuery("from CalendarEvent e where e.userId=:user order by e.startsAt",CalendarEvent.class).setParameter("user",userId).getResultList().stream().filter(e->from==null || e.endsAt.isAfter(from)).filter(e->to==null || e.startsAt.isBefore(to)).toList();}
    public List<CalendarEvent> list(Instant from,Instant to){
        var result=new ArrayList<>(events(CurrentUser.id(),from,to));
        var tasks=em.createQuery("from CampusTask t where t.userId=:user and t.status='TODO' and t.dueAt is not null",CampusTask.class).setParameter("user",CurrentUser.id()).getResultList();
        for(var t:tasks){if((from!=null && t.dueAt.isBefore(from)) || (to!=null && !t.dueAt.isBefore(to)))continue;var e=new CalendarEvent();e.id=t.id;e.userId=t.userId;e.courseId=t.courseId;e.taskId=t.id;e.title=t.title;e.eventType="DEADLINE";e.startsAt=t.dueAt;e.endsAt=t.dueAt;result.add(e);}
        result.sort(Comparator.comparing(e->e.startsAt));return result;
    }
    public CalendarEvent get(UUID id){var e=em.find(CalendarEvent.class,id);if(e==null || !e.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return e;}
    public CalendarEvent save(UUID id,Map<String,Object> input){var e=id==null?new CalendarEvent():get(id);e.userId=CurrentUser.id();if(input.get("courseId")!=null)courses.get(UUID.fromString(input.get("courseId").toString()));EntityFields.apply(e,input,"courseId","eventType","title","startsAt","endsAt","allDay","location");if(!e.endsAt.isAfter(e.startsAt))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"结束时间应晚于开始时间");if(id==null)em.persist(e);return e;}
    public void delete(UUID id){em.remove(get(id));}
    public List<CourseMeetingRule> rules(UUID courseId){courses.get(courseId);return em.createQuery("from CourseMeetingRule r where r.courseId=:course order by r.weekday,r.startsTime",CourseMeetingRule.class).setParameter("course",courseId).getResultList();}
    public CourseMeetingRule createRule(UUID courseId,Map<String,Object> input){
        var course=courses.get(courseId);var r=new CourseMeetingRule();r.courseId=courseId;EntityFields.apply(r,input,"weekday","startsTime","endsTime","validFrom","validTo","location");
        if(r.weekday<1 || r.weekday>7 || !r.endsTime.isAfter(r.startsTime) || r.validTo.isBefore(r.validFrom))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"上课时间规则无效");
        em.persist(r);var zone=timezone(CurrentUser.id());
        for(var date=r.validFrom;!date.isAfter(r.validTo);date=date.plusDays(1))if(date.getDayOfWeek().getValue()==r.weekday){var e=new CalendarEvent();e.userId=CurrentUser.id();e.courseId=courseId;e.meetingRuleId=r.id;e.eventType="CLASS";e.title=course.customName==null?course.name:course.customName;e.startsAt=date.atTime(r.startsTime).atZone(zone).toInstant();e.endsAt=date.atTime(r.endsTime).atZone(zone).toInstant();e.location=r.location;em.persist(e);}
        return r;
    }
    public void deleteRule(UUID id){var r=em.find(CourseMeetingRule.class,id);if(r==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);courses.get(r.courseId);em.createQuery("delete from CalendarEvent e where e.meetingRuleId=:id").setParameter("id",id).executeUpdate();em.remove(r);}
    public List<CalendarEvent> planStudySessions(UUID userId,List<UUID> taskIds,Instant from,Instant to){
        if(!to.isAfter(from))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择有效的学习时间范围");
        var tasks=new ArrayList<CampusTask>();
        for(var id:taskIds){var t=em.find(CampusTask.class,id);if(t==null || !t.userId.equals(userId))throw new ResponseStatusException(HttpStatus.NOT_FOUND);if(t.estimatedMinutes==null || t.estimatedMinutes<=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先填写任务预计分钟数");tasks.add(t);}
        var occupied=events(userId,from,to);var gaps=new ArrayList<Instant[]>();var cursor=from;
        for(var e:occupied){if(e.startsAt.isAfter(cursor))gaps.add(new Instant[]{cursor,e.startsAt.isBefore(to)?e.startsAt:to});if(e.endsAt.isAfter(cursor))cursor=e.endsAt;}
        if(cursor.isBefore(to))gaps.add(new Instant[]{cursor,to});
        var planned=new ArrayList<CalendarEvent>();int gapIndex=0;Instant at=gaps.isEmpty()?to:gaps.get(0)[0];
        for(var task:tasks){int remaining=task.estimatedMinutes;while(remaining>0 && gapIndex<gaps.size()){
            var gap=gaps.get(gapIndex);int available=(int)Duration.between(at,gap[1]).toMinutes();if(available<=0){gapIndex++;if(gapIndex<gaps.size())at=gaps.get(gapIndex)[0];continue;}
            int duration=Math.min(remaining,Math.min(50,available));var e=new CalendarEvent();e.userId=userId;e.courseId=task.courseId;e.taskId=task.id;e.eventType="STUDY";e.title=task.title;e.startsAt=at;e.endsAt=at.plusSeconds(duration*60L);planned.add(e);remaining-=duration;at=e.endsAt.plusSeconds(600);
        }if(remaining>0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"所选时间范围不足以完成学习安排");}
        planned.forEach(em::persist);return planned;
    }
}
