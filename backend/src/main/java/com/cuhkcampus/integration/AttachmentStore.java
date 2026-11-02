package com.cuhkcampus.integration;

import com.cuhkcampus.agent.AgentData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

@Component
public class AttachmentStore {
    private final AgentData data;
    private final Path root;
    public AttachmentStore(AgentData data,@Value("${campus.storage-root:./data/files}") String root) {this.data=data;this.root=Path.of(root).toAbsolutePath();}
    public Map<String,Object> save(UUID userId,UUID courseId,UUID nodeId,UUID mailId,String externalId,String name,String mime,String origin,byte[] bytes) {
        var existing=data.rows("select * from documents where user_id=? and origin=? and external_id=?",userId,origin,externalId);
        if(!existing.isEmpty()) {
            if(mailId!=null) data.update("update documents set mail_message_id=? where id=? and user_id=?",mailId,existing.getFirst().get("id"),userId);
            if(existing.getFirst().get("storageKey")==null) return materialize(userId,existing.getFirst(),bytes);
            return existing.getFirst();
        }
        try {
            UUID id=UUID.randomUUID();
            Files.createDirectories(root);Files.write(root.resolve(id.toString()),bytes);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            data.update("insert into documents(id,user_id,course_id,content_node_id,mail_message_id,external_id,display_name,mime_type,byte_size,sha256,storage_key,origin) values(?,?,?,?,?,?,?,?,?,?,?,?)",id,userId,courseId,nodeId,mailId,externalId,name,mime,bytes.length,hash,id.toString(),origin);
            return data.one("select * from documents where id=?",id);
        } catch(Exception e) {throw new IllegalStateException("附件保存失败",e);}
    }

    public Map<String,Object> save(UUID userId,UUID courseId,UUID nodeId,UUID mailId,String externalId,String name,String mime,String origin,Path downloaded) {
        var existing=data.rows("select * from documents where user_id=? and origin=? and external_id=?",userId,origin,externalId);
        if(!existing.isEmpty()) {
            if(existing.getFirst().get("storageKey")==null) return materialize(userId,existing.getFirst(),downloaded);
            return existing.getFirst();
        }
        try {
            UUID id=UUID.randomUUID();
            Files.createDirectories(root);
            Path target=root.resolve(id.toString());
            Files.move(downloaded,target);
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(target)){
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
            }
            String hash=HexFormat.of().formatHex(digest.digest());
            data.update("insert into documents(id,user_id,course_id,content_node_id,mail_message_id,external_id,display_name,mime_type,byte_size,sha256,storage_key,origin) values(?,?,?,?,?,?,?,?,?,?,?,?)",id,userId,courseId,nodeId,mailId,externalId,name,mime,Files.size(target),hash,id.toString(),origin);
            return data.one("select * from documents where id=?",id);
        } catch(Exception e){throw new IllegalStateException("附件保存失败",e);}
    }

    public Map<String,Object> remote(UUID userId,UUID courseId,UUID nodeId,String externalId,String name,String mime,long size,String origin) {
        var existing=data.rows("select * from documents where user_id=? and origin=? and external_id=?",userId,origin,externalId);
        if(!existing.isEmpty()) return existing.getFirst();
        UUID id=UUID.randomUUID();
        data.update("insert into documents(id,user_id,course_id,content_node_id,external_id,display_name,mime_type,byte_size,sha256,storage_key,origin) values(?,?,?,?,?,?,?,?,null,null,?)",
            id,userId,courseId,nodeId,externalId,name,mime,size,origin);
        return data.one("select * from documents where id=?",id);
    }

    public List<Map<String,Object>> list(UUID userId,String origin) {
        return data.rows("select id,storage_key from documents where user_id=? and origin=?",userId,origin);
    }

    public void delete(Map<String,Object> document) {
        try {
            if(document.get("storageKey") instanceof String key) Files.deleteIfExists(root.resolve(key));
            Path preview=root.resolve("previews").resolve(String.valueOf(document.get("id")));
            if(Files.exists(preview)) try(var paths=Files.walk(preview)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        } catch(IOException error) {
            throw new IllegalStateException("无法删除附件文件",error);
        }
    }

    private Map<String,Object> materialize(UUID userId,Map<String,Object> document,byte[] bytes) {
        try {
            UUID id=(UUID)document.get("id");
            Files.createDirectories(root);Files.write(root.resolve(id.toString()),bytes);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            data.update("update documents set byte_size=?,sha256=?,storage_key=? where id=? and user_id=?",bytes.length,hash,id.toString(),id,userId);
            return data.one("select * from documents where id=?",id);
        } catch(Exception e) {throw new IllegalStateException("附件保存失败",e);}
    }

    private Map<String,Object> materialize(UUID userId,Map<String,Object> document,Path downloaded) {
        try {
            UUID id=(UUID)document.get("id");
            Files.createDirectories(root);
            Path target=root.resolve(id.toString());
            Files.move(downloaded,target,StandardCopyOption.REPLACE_EXISTING);
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(target)) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);
            }
            String hash=HexFormat.of().formatHex(digest.digest());
            data.update("update documents set byte_size=?,sha256=?,storage_key=? where id=? and user_id=?",Files.size(target),hash,id.toString(),id,userId);
            return data.one("select * from documents where id=?",id);
        } catch(Exception e) {throw new IllegalStateException("附件保存失败",e);}
    }
}
