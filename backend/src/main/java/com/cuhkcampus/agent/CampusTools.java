package com.cuhkcampus.agent;

import com.cuhkcampus.calendar.CalendarService;
import com.cuhkcampus.document.DocumentService;
import com.cuhkcampus.integration.ConnectionService;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.util.*;

public class CampusTools {
    private final UUID userId;
    private final AgentData data;
    private final DocumentService documents;
    private final CalendarService calendar;
    private final ConnectionService connections;
    private final BlackboardApiConnector blackboard;
    private final List<Map<String,Object>> calls = new ArrayList<>();
    public CampusTools(UUID userId, AgentData data, DocumentService documents, CalendarService calendar, ConnectionService connections, BlackboardApiConnector blackboard) {
        this.userId=userId; this.data=data; this.documents=documents; this.calendar=calendar; this.connections=connections; this.blackboard=blackboard;
    }
    public List<Map<String,Object>> calls() { return calls; }
    private Object result(String name, Object arguments, Object result) {
        calls.add(Map.of("name",name,"arguments",arguments,"result",result));
        return result;
    }
    @Tool(name="list_courses", description="获取当前用户课程列表，可按学期和名称筛选")
    public Object courses(@ToolParam(required=false) String term, @ToolParam(required=false) String query) {
        var ids=blackboard.connected(userId)?blackboard.courses(userId):List.<UUID>of();
        return result("list_courses", Arrays.asList(term,query), data.rows("select * from courses where user_id=? and (?::text is null or term=?) and (?::text is null or name ilike '%'||?||'%' or code ilike '%'||?||'%') order by term desc,name",userId,term,term,query,query,query).stream().filter(c->c.get("externalId")==null || ids.contains(c.get("id"))).toList());
    }
    @Tool(name="get_course_content", description="获取课程目录或指定目录下的内容")
    public Object content(UUID courseId, @ToolParam(required=false) UUID parentId) {
        var ids=blackboard.contents(userId,courseId,parentId);
        return result("get_course_content",Arrays.asList(courseId,parentId), data.rows("select n.* from content_nodes n join courses c on c.id=n.course_id where c.user_id=? and c.id=? and n.parent_id is not distinct from ?::uuid order by n.position,n.title",userId,courseId,parentId).stream().filter(n->ids==null || n.get("externalId")==null || ids.contains(n.get("id"))).toList());
    }
    @Tool(name="list_announcements", description="查看课程公告及正文")
    public Object announcements(UUID courseId,@ToolParam(required=false) Integer limit) {
        var ids=blackboard.announcements(userId,courseId);
        return result("list_announcements",Arrays.asList(courseId,limit),data.rows("select a.* from announcements a join courses c on c.id=a.course_id where c.user_id=? and (?::uuid is null or c.id=?) order by published_at desc limit ?",userId,courseId,courseId,limit==null?20:limit).stream().filter(a->ids==null || a.get("externalId")==null || ids.contains(a.get("id"))).toList());
    }
    @Tool(name="read_course_content",description="按需读取课程内容正文和作业截止时间")
    public Object readContent(UUID contentId) {
        blackboard.node(userId,contentId);
        return result("read_course_content",List.of(contentId),data.one("select n.* from content_nodes n join courses c on c.id=n.course_id where n.id=? and c.user_id=?",contentId,userId));
    }
    @Tool(name="list_content_documents",description="按需获取指定课程内容的附件列表；先用get_course_content逐层浏览目录")
    public Object contentDocuments(UUID contentId) {
        return result("list_content_documents",List.of(contentId),blackboard.attachments(userId,contentId));
    }
    @Tool(name="search_documents",description="搜索已浏览的课程附件和本地资料；查找尚未浏览的BB文件时，使用get_course_content和list_content_documents")
    public Object searchDocuments(@ToolParam(required=false) String filename,@ToolParam(required=false) UUID courseId) {
        return result("search_documents",Arrays.asList(filename,courseId),data.rows("select * from documents where user_id=? and (?::text is null or display_name ilike '%'||?||'%') and (?::uuid is null or course_id=?) order by display_name",userId,filename,filename,courseId,courseId));
    }
    @Tool(name="read_document",description="读取当前用户文档的正文与元数据")
    public Object readDocument(UUID documentId) {
        var document=data.one("select * from documents where id=? and user_id=?",documentId,userId);
        document.put("text",documents.readText(userId,documentId));
        return result("read_document",List.of(documentId),document);
    }
    @Tool(name="list_mail",description="搜索或筛选当前用户的邮件")
    public Object mail(@ToolParam(required=false) String category,@ToolParam(required=false) UUID courseId,@ToolParam(required=false) String query,@ToolParam(required=false) Integer limit) {
        return result("list_mail",Arrays.asList(category,courseId,query,limit),data.rows("select id,subject,sender,received_at,category,importance,is_read,summary from mail_messages where user_id=? and (?::text is null or category=?) and (?::uuid is null or course_id=?) and (?::text is null or subject ilike '%'||?||'%' or body_text ilike '%'||?||'%') order by received_at desc limit ?",userId,category,category,courseId,courseId,query,query,query,limit==null?20:limit));
    }
    @Tool(name="read_mail",description="读取邮件全文与附件列表")
    public Object readMail(UUID mailId) {
        var mail=data.one("select * from mail_messages where id=? and user_id=?",mailId,userId);
        mail.put("attachments",data.rows("select * from documents where mail_message_id=? and user_id=?",mailId,userId));
        return result("read_mail",List.of(mailId),mail);
    }
    @Tool(name="list_tasks",description="查询作业、任务与校园活动，包括待确认AI建议")
    public Object tasks(@ToolParam(required=false) UUID courseId,@ToolParam(required=false) String taskType,@ToolParam(required=false) String status,@ToolParam(required=false) Instant dueFrom,@ToolParam(required=false) Instant dueTo) {
        return result("list_tasks",Arrays.asList(courseId,taskType,status,dueFrom,dueTo),data.rows("select * from tasks where user_id=? and (?::uuid is null or course_id=?) and (?::text is null or task_type=?) and (?::text is null or status=?) and (?::timestamptz is null or due_at>=?::timestamptz) and (?::timestamptz is null or due_at<=?::timestamptz) order by due_at nulls last",userId,courseId,courseId,taskType,taskType,status,status,dueFrom,dueFrom,dueTo,dueTo));
    }
    @Tool(name="create_or_update_task",description="创建待确认任务或活动，或更新已有任务。新建项状态固定为SUGGESTED，用户在任务页确认。")
    public Object saveTask(@ToolParam(required=false) UUID taskId,String taskType,String title,
            @ToolParam(required=false) UUID courseId,@ToolParam(required=false) String description,
            @ToolParam(required=false) String status,@ToolParam(required=false) String priority,
            @ToolParam(required=false) Instant dueAt,@ToolParam(required=false) Integer estimatedMinutes,
            @ToolParam(required=false) Instant startsAt,@ToolParam(required=false) Instant endsAt,
            @ToolParam(required=false) String location,@ToolParam(required=false) String organizer,@ToolParam(required=false) String category) {
        if(courseId!=null) data.one("select id from courses where id=? and user_id=?",courseId,userId);
        UUID id=taskId==null?UUID.randomUUID():taskId;
        if(taskId==null) {
            data.update("insert into tasks(id,user_id,course_id,task_type,title,description,status,priority,due_at,estimated_minutes,starts_at,ends_at,location,organizer,category,starred) values(?,?,?,?,?,?,'SUGGESTED',?,?::timestamptz,?,?::timestamptz,?::timestamptz,?,?,?,false)",id,userId,courseId,taskType,title,description,priority==null?"NORMAL":priority,dueAt,estimatedMinutes,startsAt,endsAt,location,organizer,category);
        } else {
            data.one("select id from tasks where id=? and user_id=?",id,userId);
            data.update("update tasks set course_id=?,task_type=?,title=?,description=?,status=coalesce(?,status),priority=coalesce(?,priority),due_at=?::timestamptz,estimated_minutes=?,starts_at=?::timestamptz,ends_at=?::timestamptz,location=?,organizer=?,category=? where id=? and user_id=?",courseId,taskType,title,description,status,priority,dueAt,estimatedMinutes,startsAt,endsAt,location,organizer,category,id,userId);
        }
        return result("create_or_update_task",Arrays.asList(taskId,taskType,title,courseId),data.one("select * from tasks where id=? and user_id=?",id,userId));
    }
    @Tool(name="get_calendar_events",description="查询指定时间范围的课程和日历事件")
    public Object calendar(Instant from,Instant to) {
        return result("get_calendar_events",List.of(from,to),data.rows("select * from calendar_events where user_id=? and starts_at<?::timestamptz and ends_at>?::timestamptz order by starts_at",userId,to,from));
    }
    @Tool(name="plan_study_sessions",description="根据任务耗时与可用时间创建学习日程，50分钟学习10分钟休息")
    public Object plan(List<UUID> taskIds,Instant from,Instant to) {
        return result("plan_study_sessions",List.of(taskIds,from,to),calendar.planStudySessions(userId,taskIds,from,to));
    }
    @Tool(name="sync_outlook",description="同步已连接的OUTLOOK邮件")
    public Object syncOutlook() {
        return result("sync_outlook",List.of(),connections.syncOutlook(userId));
    }
}
