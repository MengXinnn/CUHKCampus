package com.cuhkcampus.integration.blackboard;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import com.cuhkcampus.document.Document;
import com.cuhkcampus.integration.AttachmentStore;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;

@Service
public class BlackboardApiConnector {
    private static final String API = "/learn/api/public/v1";
    private static final Set<String> ATTACHMENT_HANDLERS = Set.of(
        "resource/x-bb-file", "resource/x-bb-document", "resource/x-bb-assignment");
    private final AgentData data;
    private final CredentialCipher cipher;
    private final AttachmentStore files;
    private final BlackboardHttpClient http;
    private final URI baseUrl;
    private final org.springframework.beans.factory.ObjectProvider<BlackboardLoginService> login;

    public BlackboardApiConnector(AgentData data, CredentialCipher cipher, AttachmentStore files,
            @Value("${campus.blackboard.base-url:https://bb.cuhk.edu.cn}") String baseUrl,
            @Value("${campus.blackboard.curl-binary:curl}") String curl, org.springframework.beans.factory.ObjectProvider<BlackboardLoginService> login) {
        this.login=login;
        this.data = data;
        this.cipher = cipher;
        this.files = files;
        this.baseUrl = URI.create(baseUrl);
        this.http = new BlackboardHttpClient(this.baseUrl, data.json, curl);
    }

    public Map<String, Object> connect(UUID userId, String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 BB 登录会话 Cookie");
        String cookie = cookieHeader.strip();
        if (cookie.regionMatches(true, 0, "Cookie:", 0, 7)) cookie = cookie.substring(7).strip();
        try {
            Map<String, Object> account = http.user(cookie);
            String encrypted = data.json(Map.of("encrypted", cipher.encrypt(data.json(Map.of("cookieHeader", cookie)))));
            data.update("insert into external_connections(id,user_id,provider,account_identifier,credentials,status) values(?,?,'BLACKBOARD',?,?::jsonb,'CONNECTED') on conflict(user_id,provider) do update set account_identifier=excluded.account_identifier,credentials=excluded.credentials,status='CONNECTED'",
                UUID.randomUUID(), userId, account.get("userName"), encrypted);
            return Map.of("provider", "BLACKBOARD", "status", "CONNECTED", "accountIdentifier", account.get("userName"));
        } catch (BlackboardHttpClient.SessionExpiredException e) { throw expired(userId); }
    }

    public boolean connected(UUID userId) {
        return !data.rows("select id from external_connections where user_id=? and provider='BLACKBOARD'", userId).isEmpty();
    }

    private <T> T request(UUID userId, Function<String, T> operation) {
        try { return operation.apply(cookie(userId)); }
        catch (BlackboardHttpClient.SessionExpiredException e) {
            var service=login.getObject();
            if(!service.automatic(userId))throw expired(userId);
            service.login(userId);
            try{return operation.apply(cookie(userId));}
            catch(BlackboardHttpClient.SessionExpiredException again){throw expired(userId);}
        }
    }

    /** Only course metadata; no content, announcement or attachment requests. */
    public List<UUID> courses(UUID userId) {
        return request(userId, cookie -> {
            var memberships = http.list(cookie, API + "/users/me/courses?limit=100");
            Set<String> mergedChildren = new HashSet<>();
            for (var membership : memberships)
                if (membership.get("childCourseId") instanceof String child) mergedChildren.add(child);
            var terms = new HashMap<String, String>();
            var ids = new ArrayList<UUID>();
            for (var membership : memberships) {
                String externalId = (String) membership.get("courseId");
                if (mergedChildren.contains(externalId)) continue;
                var course = http.object(cookie, API + "/courses/" + externalId);
                String termId = (String) course.get("termId");
                String term = termId == null ? null : terms.computeIfAbsent(termId,
                    id -> (String) http.object(cookie, API + "/terms/" + id).get("name"));
                UUID id = UUID.randomUUID();
                data.update("insert into courses(id,user_id,external_id,code,name,term) values(?,?,?,?,?,?) on conflict(user_id,external_id) do update set code=excluded.code,name=excluded.name,term=excluded.term",
                    id, userId, externalId, course.get("courseId"), course.get("name"), term);
                ids.add((UUID) data.one("select id from courses where user_id=? and external_id=?", userId, externalId).get("id"));
            }
            return ids;
        });
    }

    private Map<String,Object> course(UUID userId, UUID courseId) {
        return data.one("select * from courses where id=? and user_id=?", courseId, userId);
    }

    public List<Map<String,Object>> grades(UUID userId, UUID courseId) {
        var local=course(userId,courseId);
        if(local.get("externalId")==null)return List.of();
        return request(userId,cookie->{
            String path=API+"/courses/"+local.get("externalId")+"/gradebook";
            var columns=http.list(cookie,path+"/columns?limit=100");
            var grades=http.list(cookie,path+"/users/me?limit=100");
            var result=new ArrayList<Map<String,Object>>();
            for(var column:columns){
                var row=new HashMap<String,Object>(column);
                grades.stream().filter(grade->column.get("id").equals(grade.get("columnId"))).findFirst().ifPresent(grade->row.put("grade",grade));
                result.add(row);
            }
            return result;
        });
    }

    public void courseDetail(UUID userId, UUID courseId) {
        var local = course(userId, courseId);
        if (local.get("externalId") == null) return;
        request(userId, cookie -> {
            String externalId = (String) local.get("externalId");
            var remote = http.object(cookie, API + "/courses/" + externalId);
            data.update("update courses set code=?,name=?,teacher=? where id=? and user_id=?",
                remote.get("courseId"), remote.get("name"), teachers(cookie, externalId), courseId, userId);
            return null;
        });
    }

    /** Reads exactly one directory level. */
    public List<UUID> contents(UUID userId, UUID courseId, UUID parentId) {
        var local = course(userId, courseId);
        if (local.get("externalId") == null) return null;
        String path = API + "/courses/" + local.get("externalId") + "/contents";
        if (parentId != null) {
            var parent = data.one("select external_id from content_nodes where id=? and course_id=?", parentId, courseId);
            path += "/" + parent.get("externalId") + "/children";
        }
        String directory = path;
        return request(userId, cookie -> {
            var ids = new ArrayList<UUID>();
            for (var content : http.list(cookie, directory + "?limit=100")) {
                if (content.get("availability") instanceof Map<?,?> availability && "No".equals(availability.get("available"))) continue;
                var handler = (Map<?,?>) content.get("contentHandler");
                String handlerId = handler == null ? null : (String) handler.get("id");
                String source = source(content);
                String type = Boolean.TRUE.equals(content.get("hasChildren")) ? "FOLDER"
                    : "resource/x-bb-assignment".equals(handlerId) ? "ASSIGNMENT"
                    : Set.of("resource/x-bb-externallink", "resource/x-bb-link", "resource/x-bb-video").contains(handlerId == null ? "" : handlerId) ? "LINK" : "ITEM";
                data.update("insert into content_nodes(id,course_id,parent_id,external_id,node_type,title,position,source_url) values(?,?,?,?,?,?,?,?) on conflict(course_id,external_id) do update set parent_id=excluded.parent_id,node_type=excluded.node_type,title=excluded.title,position=excluded.position,source_url=excluded.source_url",
                    UUID.randomUUID(), courseId, parentId, content.get("id"), type, content.get("title"), content.getOrDefault("position", 0), source);
                ids.add((UUID) data.one("select id from content_nodes where course_id=? and external_id=?", courseId, content.get("id")).get("id"));
            }
            return ids;
        });
    }

    private String source(Map<String,Object> content) {
        if(content.get("contentHandler") instanceof Map<?,?> handler && handler.get("url") instanceof String url) return baseUrl.resolve(url).toString();
        if(content.get("links") instanceof List<?> links)
            for(var value:links) {
                var link=(Map<?,?>)value;
                if("alternate".equals(link.get("rel"))) return baseUrl.resolve((String)link.get("href")).toString();
            }
        return null;
    }

    @SuppressWarnings("unchecked")
    public void node(UUID userId, UUID nodeId) {
        var local = data.one("select n.*,c.external_id as external_course_id from content_nodes n join courses c on c.id=n.course_id where n.id=? and c.user_id=?", nodeId, userId);
        if (local.get("externalCourseId") == null) return;
        request(userId, cookie -> {
            String courseId = (String) local.get("externalCourseId");
            String contentId = (String) local.get("externalId");
            var detail = http.object(cookie, "/learn/api/v1/courses/" + courseId + "/contents/" + contentId);
            var body = (Map<String,Object>) detail.get("body");
            String html = body == null ? "" : (String) body.get("displayText");
            Instant due = null;
            if ("ASSIGNMENT".equals(local.get("nodeType"))) {
                var content = http.object(cookie, API + "/courses/" + courseId + "/contents/" + contentId);
                var handler = (Map<String,Object>) content.get("contentHandler");
                if (handler.get("gradeColumnId") instanceof String columnId) {
                    var column = http.object(cookie, API + "/courses/" + courseId + "/gradebook/columns/" + columnId);
                    var grading = (Map<String,Object>) column.get("grading");
                    if (grading != null && grading.get("due") instanceof String value) due = Instant.parse(value);
                }
            }
            data.update("update content_nodes set body_html=?,body_text=?,due_at=? where id=?",
                html, Jsoup.parse(html == null ? "" : html).wholeText(), due, nodeId);
            return null;
        });
    }

    public List<Map<String,Object>> attachments(UUID userId, UUID nodeId) {
        var local = data.one("select n.*,c.external_id as external_course_id from content_nodes n join courses c on c.id=n.course_id where n.id=? and c.user_id=?", nodeId, userId);
        if (local.get("externalCourseId") == null)
            return data.rows("select * from documents where content_node_id=? and user_id=?", nodeId, userId);
        return request(userId, cookie -> {
            String path = API + "/courses/" + local.get("externalCourseId") + "/contents/" + local.get("externalId");
            var content = http.object(cookie, path);
            var handler = (Map<?,?>) content.get("contentHandler");
            if (handler == null || !ATTACHMENT_HANDLERS.contains(handler.get("id"))) return List.of();
            var result = new ArrayList<Map<String,Object>>();
            for (var attachment : http.list(cookie, path + "/attachments?limit=100")) {
                Object size = attachment.get("size");
                result.add(files.remote(userId, (UUID) local.get("courseId"), nodeId,
                    path + "/attachments/" + attachment.get("id"), (String) attachment.get("fileName"),
                    (String) attachment.get("mimeType"), size instanceof Number number ? number.longValue() : 0, "BLACKBOARD"));
            }
            return result;
        });
    }

    public List<UUID> announcements(UUID userId, UUID courseId) {
        var local = course(userId, courseId);
        if (local.get("externalId") == null) return null;
        return request(userId, cookie -> {
            var ids = new ArrayList<UUID>();
            for (var announcement : http.list(cookie, API + "/courses/" + local.get("externalId") + "/announcements?limit=100")) {
                String html = (String) announcement.get("body");
                data.update("insert into announcements(id,course_id,external_id,title,body_html,body_text,published_at) values(?,?,?,?,?,?,?) on conflict(course_id,external_id) do update set title=excluded.title,body_html=excluded.body_html,body_text=excluded.body_text,published_at=excluded.published_at",
                    UUID.randomUUID(), courseId, announcement.get("id"), announcement.get("title"), html,
                    Jsoup.parse(html == null ? "" : html).wholeText(), Instant.parse((String) announcement.get("created")));
                ids.add((UUID) data.one("select id from announcements where course_id=? and external_id=?", courseId, announcement.get("id")).get("id"));
            }
            return ids;
        });
    }

    public void announcement(UUID userId, UUID id) {
        var local = data.one("select a.*,c.external_id as external_course_id from announcements a join courses c on c.id=a.course_id where a.id=? and c.user_id=?", id, userId);
        if (local.get("externalCourseId") == null) return;
        request(userId, cookie -> {
            var remote = http.object(cookie, API + "/courses/" + local.get("externalCourseId") + "/announcements/" + local.get("externalId"));
            String html = (String) remote.get("body");
            data.update("update announcements set title=?,body_html=?,body_text=?,published_at=? where id=?",
                remote.get("title"), html, Jsoup.parse(html == null ? "" : html).wholeText(), Instant.parse((String) remote.get("created")), id);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private String cookie(UUID userId) {
        var connection = data.one("select credentials from external_connections where user_id=? and provider='BLACKBOARD'", userId);
        var encrypted = (Map<String, Object>) connection.get("credentials");
        return (String) data.object(cipher.decrypt((String) encrypted.get("encrypted"))).get("cookieHeader");
    }

    private ResponseStatusException expired(UUID userId) {
        data.update("update external_connections set status='LOGIN_REQUIRED' where user_id=? and provider='BLACKBOARD'", userId);
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "BB 会话已过期，请重新绑定");
    }

    @SuppressWarnings("unchecked")
    private String teachers(String cookie, String externalCourseId) {
        var names = new ArrayList<String>();
        for (Map<String, Object> membership : http.list(cookie,
                API + "/courses/" + externalCourseId + "/users?role=Instructor&expand=user&limit=100")) {
            Map<String, Object> user = (Map<String, Object>) membership.get("user");
            Map<String, Object> name = (Map<String, Object>) user.get("name");
            names.add((String.valueOf(name.getOrDefault("given", "")) + " " + name.getOrDefault("family", "")).strip());
        }
        return String.join(", ", names);
    }

    public void materialize(UUID userId, Document document) {
        materialize(userId, document, progress -> {});
    }

    public void materialize(UUID userId, Document document, Consumer<BlackboardHttpClient.DownloadProgress> progress) {
        if (!"BLACKBOARD".equals(document.origin) || document.externalId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档没有可拉取的 BB 来源");
        Path downloaded = null;
        try {
            downloaded = Files.createTempFile("campus-bb-download-", ".tmp");
            Path target=downloaded;
            request(userId,cookie -> {http.download(cookie, document.externalId + "/download", target, document.byteSize, progress);return null;});
            files.save(userId, document.courseId, document.contentNodeId, document.mailMessageId,
                document.externalId, document.displayName, document.mimeType, "BLACKBOARD", downloaded);
        } catch (BlackboardHttpClient.SessionExpiredException e) { throw expired(userId); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存 BB 附件", e); }
        finally {
            if (downloaded != null) try { Files.deleteIfExists(downloaded); }
            catch (IOException e) { throw new IllegalStateException("无法清理 BB 附件临时文件", e); }
        }
    }

    public void purge(UUID userId) {
        for(var document:files.list(userId,"BLACKBOARD")) files.delete(document);
        data.update("delete from tasks where user_id=? and course_id in (select id from courses where user_id=? and external_id is not null)",userId,userId);
        data.update("delete from courses where user_id=? and external_id is not null",userId);
        data.update("delete from briefings where user_id=?",userId);
        data.update("delete from external_connections where user_id=? and provider='BLACKBOARD'", userId);
        data.update("delete from documents where user_id=? and origin='BLACKBOARD'",userId);
    }

    public void disconnect(UUID userId) {purge(userId);}

}
