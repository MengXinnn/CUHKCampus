package com.cuhkcampus.mail;
import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.course.CourseService;
import com.cuhkcampus.course.EntityFields;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
@Transactional
public class MailService {
    @PersistenceContext EntityManager em;
    final CourseService courses;
    public MailService(CourseService courses){this.courses=courses;}
    public List<MailMessage> list(String category,UUID courseId,Boolean isRead,String q){return em.createQuery("from MailMessage m where m.userId=:user order by m.receivedAt desc",MailMessage.class).setParameter("user",CurrentUser.id()).getResultList().stream()
        .filter(m->category==null || category.equals(m.category)).filter(m->courseId==null || courseId.equals(m.courseId)).filter(m->isRead==null || isRead==m.isRead)
        .filter(m->q==null || (m.subject+" "+m.bodyText).toLowerCase().contains(q.toLowerCase())).toList();}
    public MailMessage get(UUID id){var m=em.find(MailMessage.class,id);if(m==null || !m.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return m;}
    public MailMessage patch(UUID id,Map<String,Object> input){var m=get(id);if(input.get("courseId")!=null)courses.get(UUID.fromString(input.get("courseId").toString()));EntityFields.apply(m,input,"isRead","category","importance","courseId");return m;}
}
