package com.cuhkcampus.integration.outlook;

import com.cuhkcampus.agent.AgentData;
import com.cuhkcampus.common.CredentialCipher;
import com.cuhkcampus.integration.AttachmentStore;
import com.cuhkcampus.integration.SyncProgress;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import jakarta.servlet.http.HttpSession;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

@Service
public class OutlookSyncService {
    private static final String GRAPH="https://graph.microsoft.com/v1.0";
    private static final String AUTH="https://login.microsoftonline.com/common/oauth2/v2.0";
    private static final String SCOPES="offline_access User.Read Mail.Read Calendars.ReadWrite";
    private final AgentData data;
    private final CredentialCipher cipher;
    private final AttachmentStore files;
    private final String clientId;
    private final String redirectUri;
    private final RestClient http;
    public OutlookSyncService(AgentData data,CredentialCipher cipher,AttachmentStore files,
            @Value("${campus.outlook.client-id:}") String clientId,
            @Value("${campus.outlook.redirect-uri:http://localhost:8080/api/v1/connections/outlook/callback}") String redirectUri,RestClient.Builder http) {
        this.data=data;this.cipher=cipher;this.files=files;this.clientId=clientId;this.redirectUri=redirectUri;this.http=http.build();
    }
    public String authorize(UUID userId,HttpSession session) {
        String configuredClientId=clientId;
        if(configuredClientId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Outlook 服务端应用尚未配置");
        String verifier=random(),state=random();
        session.setAttribute("outlookState",state);session.setAttribute("outlookVerifier",verifier);session.setAttribute("outlookUser",userId);session.setAttribute("outlookClientId",configuredClientId);
        try {
            String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return UriComponentsBuilder.fromUriString(AUTH+"/authorize").queryParam("client_id",configuredClientId).queryParam("response_type","code")
                    .queryParam("redirect_uri",redirectUri).queryParam("response_mode","query").queryParam("scope",SCOPES)
                    .queryParam("state",state).queryParam("code_challenge",challenge).queryParam("code_challenge_method","S256").build().encode().toUriString();
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }

    public void callback(String code,String state,HttpSession session) {
        if(!Objects.equals(state,session.getAttribute("outlookState")) || session.getAttribute("outlookUser")==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Outlook 授权会话无效");
        UUID userId=(UUID)session.getAttribute("outlookUser");
        String authorizedClientId=(String)session.getAttribute("outlookClientId");
        var form=new LinkedMultiValueMap<String,String>();
        form.add("client_id",authorizedClientId);form.add("grant_type","authorization_code");form.add("code",code);
        form.add("redirect_uri",redirectUri);form.add("code_verifier",(String)session.getAttribute("outlookVerifier"));
        Map<String,Object> token=token(form);
        token.put("client_id",authorizedClientId);
        Map<String,Object> me=get(GRAPH+"/me",(String)token.get("access_token"));
        Object account=me.get("mail")!=null?me.get("mail"):me.get("userPrincipalName");
        data.update("insert into external_connections(id,user_id,provider,account_identifier,credentials,status) values(?,?,'OUTLOOK',?,?::jsonb,'CONNECTED') on conflict(user_id,provider) do update set account_identifier=excluded.account_identifier,credentials=excluded.credentials,status='CONNECTED'",UUID.randomUUID(),userId,account,encrypted(token));
        session.removeAttribute("outlookState");session.removeAttribute("outlookVerifier");session.removeAttribute("outlookUser");session.removeAttribute("outlookClientId");
        discoverFolders(userId);
    }
    private String random() {byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private Map<String,Object> token(LinkedMultiValueMap<String,String> form) {
        try {
            var result=data.object(http.post().uri(AUTH+"/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class));
            result.put("expires_at",Instant.now().plusSeconds(((Number)result.get("expires_in")).longValue()).toString());return result;
        } catch(RestClientResponseException error) {throw upstream(error);}
    }
    private String encrypted(Map<String,Object> token) {return data.json(Map.of("encrypted",cipher.encrypt(data.json(token))));}
    private Map<String,Object> connection(UUID userId) {return data.one("select * from external_connections where user_id=? and provider='OUTLOOK'",userId);}
    @SuppressWarnings("unchecked")
    private String accessToken(UUID userId) {
        var connection=connection(userId);
        var credentials=(Map<String,Object>)connection.get("credentials");
        var token=data.object(cipher.decrypt((String)credentials.get("encrypted")));
        if(Instant.parse((String)token.get("expires_at")).isBefore(Instant.now())) {
            var form=new LinkedMultiValueMap<String,String>();form.add("client_id",(String)token.get("client_id"));form.add("grant_type","refresh_token");form.add("refresh_token",(String)token.get("refresh_token"));form.add("scope",SCOPES);
            token.putAll(token(form));
            data.update("update external_connections set credentials=?::jsonb where id=?",encrypted(token),connection.get("id"));
        }
        return (String)token.get("access_token");
    }
    private Map<String,Object> get(String url,String token) {
        try {return data.object(http.get().uri(URI.create(url)).header("Authorization","Bearer "+token).header("Prefer","IdType=\"ImmutableId\"").retrieve().body(String.class));}
        catch(RestClientResponseException error) {throw upstream(error);}
    }
    private ResponseStatusException upstream(RestClientResponseException error) {
        var payload=data.object(error.getResponseBodyAsString());
        String detail=payload.get("error_description") instanceof String description?description:
                payload.get("error") instanceof Map<?,?> graphError?String.valueOf(graphError.get("message")):String.valueOf(payload.get("error"));
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Outlook 请求失败（HTTP "+error.getStatusCode().value()+"）："+detail);
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> values(Map<String,Object> response) {return (List<Map<String,Object>>)response.get("value");}
    public List<Map<String,Object>> folders(UUID userId) {
        discoverFolders(userId);
        return data.rows("select f.id,f.external_id,f.display_name,f.selected,f.last_synced_at from mail_folders f join external_connections c on c.id=f.connection_id where c.user_id=? and c.provider='OUTLOOK' order by f.display_name",userId);
    }
    private void discoverFolders(UUID userId) {
        var connection=connection(userId);String token=accessToken(userId);
        discover((UUID)connection.get("id"),GRAPH+"/me/mailFolders?$top=100",token,"");
    }
    private void discover(UUID connectionId,String firstUrl,String token,String prefix) {
        String url=firstUrl;
        while(url!=null) {
            var response=get(url,token);
            for(var folder:values(response)) {
                String name=prefix+folder.get("displayName");
                data.update("insert into mail_folders(id,connection_id,external_id,display_name,selected) values(?,?,?,?,false) on conflict(connection_id,external_id) do update set display_name=excluded.display_name",UUID.randomUUID(),connectionId,folder.get("id"),name);
                if(((Number)folder.get("childFolderCount")).intValue()>0)
                    discover(connectionId,GRAPH+"/me/mailFolders/"+folder.get("id")+"/childFolders?$top=100",token,name+" / ");
            }
            url=(String)response.get("@odata.nextLink");
        }
    }
    public List<Map<String,Object>> select(UUID userId,List<UUID> folderIds) {
        var connection=connection(userId);
        data.update("update mail_folders set selected=false where connection_id=?",connection.get("id"));
        for(UUID id:folderIds) data.update("update mail_folders set selected=true where id=? and connection_id=?",id,connection.get("id"));
        return data.rows("select id,external_id,display_name,selected,last_synced_at from mail_folders where connection_id=? order by display_name",connection.get("id"));
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> sync(UUID userId) {
        return sync(userId,progress -> {});
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> sync(UUID userId,Consumer<SyncProgress> progress) {
        progress.accept(new SyncProgress("CONNECTING","正在读取 Outlook 文件夹",0,0));
        var connection=connection(userId);String token=accessToken(userId);int synced=0;
        var folders=data.rows("select * from mail_folders where connection_id=? and selected=true",connection.get("id"));
        progress.accept(new SyncProgress("FOLDERS","文件夹已获取",0,folders.size()));
        for(int folderIndex=0;folderIndex<folders.size();folderIndex++) {
            var folder=folders.get(folderIndex);
            progress.accept(new SyncProgress("FOLDERS","正在同步 " + folder.get("displayName"),folderIndex,folders.size()));
            String url=folder.get("deltaLink")==null?GRAPH+"/me/mailFolders/"+folder.get("externalId")+"/messages/delta?$top=100":(String)folder.get("deltaLink");
            while(url!=null) {
                var response=get(url,token);
                for(var change:values(response)) {
                    if(change.containsKey("@removed")) {data.update("delete from mail_messages where user_id=? and external_id=? and folder_id=?",userId,change.get("id"),folder.get("id"));continue;}
                    var message=get(GRAPH+"/me/messages/"+change.get("id")+"?$select=id,subject,from,receivedDateTime,body,webLink,importance,isRead,hasAttachments",token);
                    saveMessage(userId,(UUID)folder.get("id"),message,token);
                    synced++;
                    progress.accept(new SyncProgress("MESSAGES","已读取 " + synced + " 封邮件",folderIndex,folders.size()));
                }
                url=(String)response.get("@odata.nextLink");
                if(response.containsKey("@odata.deltaLink")) data.update("update mail_folders set delta_link=?,last_synced_at=now() where id=?",response.get("@odata.deltaLink"),folder.get("id"));
            }
            progress.accept(new SyncProgress("FOLDERS",String.valueOf(folder.get("displayName")),folderIndex+1,folders.size()));
        }
        data.update("update external_connections set last_synced_at=now() where id=?",connection.get("id"));
        return Map.of("provider","OUTLOOK","synced",synced);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> saveMessage(UUID userId,UUID folderId,Map<String,Object> message,String token) {
        var existing=data.rows("select id from mail_messages where user_id=? and external_id=?",userId,message.get("id"));
        UUID mailId=existing.isEmpty()?UUID.randomUUID():(UUID)existing.getFirst().get("id");
        var body=(Map<String,Object>)message.get("body");
        String html="html".equalsIgnoreCase((String)body.get("contentType"))?(String)body.get("content"):null;
        String text=html==null?(String)body.get("content"):Jsoup.parse(html).text();
        String importance="high".equals(message.get("importance"))?"HIGH":"low".equals(message.get("importance"))?"LOW":"NORMAL";
        if(existing.isEmpty()) data.update("insert into mail_messages(id,user_id,folder_id,external_id,subject,sender,received_at,body_html,body_text,web_link,category,importance,is_read) values(?,?,?,?,?,?::jsonb,?::timestamptz,?,?,?,'GENERAL',?,?)",mailId,userId,folderId,message.get("id"),message.get("subject"),data.json(message.get("from")),message.get("receivedDateTime"),html,text,message.get("webLink"),importance,message.get("isRead"));
        else data.update("update mail_messages set folder_id=?,subject=?,sender=?::jsonb,body_html=?,body_text=?,web_link=?,importance=?,is_read=? where id=? and user_id=?",folderId,message.get("subject"),data.json(message.get("from")),html,text,message.get("webLink"),importance,message.get("isRead"),mailId,userId);
        if(Boolean.TRUE.equals(message.get("hasAttachments"))) attachments(userId,mailId,(String)message.get("id"),token);
        return data.one("select * from mail_messages where id=? and user_id=?",mailId,userId);
    }
    public List<Map<String,Object>> search(UUID userId,String query,int limit) {
        discoverFolders(userId);
        String token=accessToken(userId);
        String url=UriComponentsBuilder.fromUriString(GRAPH+"/me/messages")
                .queryParam("$search","\""+query.replace("\\","\\\\").replace("\"","\\\"")+"\"")
                .queryParam("$top",limit)
                .queryParam("$select","id,parentFolderId,subject,from,receivedDateTime,body,webLink,importance,isRead,hasAttachments")
                .build().encode().toUriString();
        var result=new ArrayList<Map<String,Object>>();
        for(var message:values(get(url,token))) {
            UUID folderId=(UUID)data.one("select f.id from mail_folders f join external_connections c on c.id=f.connection_id where c.user_id=? and c.provider='OUTLOOK' and f.external_id=?",userId,message.get("parentFolderId")).get("id");
            result.add(saveMessage(userId,folderId,message,token));
        }
        return result;
    }
    public Map<String,Object> createCalendarEvent(UUID userId,UUID eventId) {
        var event=data.one("select * from calendar_events where id=? and user_id=?",eventId,userId);
        String timezone=data.jdbc.sql("select timezone from users where id=?").param(userId).query(String.class).single();
        ZoneId zone=ZoneId.of(timezone);
        var payload=new LinkedHashMap<String,Object>();
        payload.put("subject",event.get("title"));
        payload.put("start",Map.of("dateTime",Instant.parse((String)event.get("startsAt")).atZone(zone).toLocalDateTime().toString(),"timeZone",timezone));
        payload.put("end",Map.of("dateTime",Instant.parse((String)event.get("endsAt")).atZone(zone).toLocalDateTime().toString(),"timeZone",timezone));
        payload.put("isAllDay",event.get("allDay"));
        if(event.get("location")!=null)payload.put("location",Map.of("displayName",event.get("location")));
        try {
            var created=data.object(http.post().uri(GRAPH+"/me/events").headers(headers->headers.setBearerAuth(accessToken(userId)))
                    .contentType(MediaType.APPLICATION_JSON).body(data.json(payload)).retrieve().body(String.class));
            return Map.of("id",created.get("id"),"webLink",created.get("webLink"));
        } catch(RestClientResponseException error) {throw upstream(error);}
    }
    private void attachments(UUID userId,UUID mailId,String externalMailId,String token) {
        String url=GRAPH+"/me/messages/"+externalMailId+"/attachments?$top=100";
        while(url!=null) {
            var response=get(url,token);
            for(var attachment:values(response)) {
                if("#microsoft.graph.fileAttachment".equals(attachment.get("@odata.type")))
                    files.save(userId,null,null,mailId,externalMailId+":"+attachment.get("id"),(String)attachment.get("name"),(String)attachment.get("contentType"),"OUTLOOK",Base64.getDecoder().decode((String)attachment.get("contentBytes")));
            }
            url=(String)response.get("@odata.nextLink");
        }
    }
}
