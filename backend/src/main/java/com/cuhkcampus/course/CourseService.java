package com.cuhkcampus.course;

import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import com.cuhkcampus.document.Document;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service
@Transactional
public class CourseService {
    @PersistenceContext EntityManager em;
    private final BlackboardApiConnector blackboard;
    public CourseService(BlackboardApiConnector blackboard) { this.blackboard=blackboard; }
    public List<Course> list(String term, String query) {
        var remoteIds = blackboard.connected(CurrentUser.id()) ? blackboard.courses(CurrentUser.id()) : List.<UUID>of();
        return em.createQuery("from Course c where c.userId=:user order by c.code, c.name", Course.class)
            .setParameter("user", CurrentUser.id()).getResultList().stream()
            .filter(c -> c.externalId == null || remoteIds.contains(c.id))
            .filter(c -> term == null || term.equals(c.term))
            .filter(c -> query == null || ((c.code == null ? "" : c.code) + " " + c.name).toLowerCase().contains(query.toLowerCase())).toList();
    }
    public Course get(UUID id) {
        var c=em.find(Course.class,id);
        if(c==null || !c.userId.equals(CurrentUser.id())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return c;
    }
    public Course detail(UUID id) {
        var course=get(id);
        if(course.externalId!=null) {blackboard.courseDetail(CurrentUser.id(),id);em.refresh(course);}
        return course;
    }
    public List<Map<String,Object>> attachments(UUID id) {nodeRecord(id);return blackboard.attachments(CurrentUser.id(),id);}
    public Course save(UUID id, Map<String,Object> input) {
        var c=id==null?new Course():get(id); c.userId=CurrentUser.id();
        EntityFields.apply(c,input,"code","name","customName","term","teacher","color","archived","note");
        if(id==null)em.persist(c); return c;
    }
    public List<ContentNode> content(UUID id, UUID parentId) {
        get(id); var remoteIds=blackboard.contents(CurrentUser.id(),id,parentId); return em.createQuery("from ContentNode n where n.courseId=:course and ((:parent is null and n.parentId is null) or n.parentId=:parent) order by n.position,n.title",ContentNode.class)
            .setParameter("course",id).setParameter("parent",parentId).getResultList().stream().filter(n->remoteIds==null || n.externalId==null || remoteIds.contains(n.id)).toList();
    }
    private ContentNode nodeRecord(UUID id) {
        var n=em.find(ContentNode.class,id); if(n==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND); get(n.courseId);return n;
    }
    public ContentNode node(UUID id) {
        var node=nodeRecord(id);
        if(node.externalId!=null) {blackboard.node(CurrentUser.id(),id);em.refresh(node);}
        return node;
    }
    public List<Announcement> announcements(UUID courseId) {
        if(courseId==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请选择课程查看公告");
        get(courseId);
        var remoteIds=blackboard.announcements(CurrentUser.id(),courseId);
        return em.createQuery("select a from Announcement a, Course c where a.courseId=c.id and c.userId=:user and c.id=:course order by a.publishedAt desc",Announcement.class)
            .setParameter("user",CurrentUser.id()).setParameter("course",courseId).getResultList().stream().filter(a->remoteIds==null || a.externalId==null || remoteIds.contains(a.id)).toList();
    }
    private Announcement announcementRecord(UUID id) {
        var a=em.find(Announcement.class,id);if(a==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);get(a.courseId);return a;
    }
    public Announcement announcement(UUID id) {
        var announcement=announcementRecord(id);
        if(announcement.externalId!=null) {blackboard.announcement(CurrentUser.id(),id);em.refresh(announcement);}
        return announcement;
    }
    public Announcement markAnnouncement(UUID id, Map<String,Object> body) {
        var a=announcementRecord(id);EntityFields.apply(a,body,"isRead");return a;
    }
    public List<CourseNote> notes(UUID courseId) {
        get(courseId);return em.createQuery("from CourseNote n where n.courseId=:course and n.userId=:user order by n.title",CourseNote.class)
            .setParameter("course",courseId).setParameter("user",CurrentUser.id()).getResultList();
    }
    public CourseNote note(UUID id) {
        var n=em.find(CourseNote.class,id);if(n==null || !n.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return n;
    }
    public CourseNote saveNote(UUID courseId,UUID id,Map<String,Object> input) {
        var n=id==null?new CourseNote():note(id);
        if(id==null){get(courseId);n.courseId=courseId;n.userId=CurrentUser.id();}
        if(input.get("documentId")!=null){var d=em.find(Document.class,UUID.fromString(input.get("documentId").toString()));if(d==null || !d.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
        EntityFields.apply(n,input,"title","markdown","documentId");if(id==null)em.persist(n);return n;
    }
    public void deleteNote(UUID id){em.remove(note(id));}
}
