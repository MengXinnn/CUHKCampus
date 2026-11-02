package com.cuhkcampus.document;

import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.course.EntityFields;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.io.IOException;

@Service
@Transactional
public class DocumentFolderService {
    @PersistenceContext EntityManager em;
    private final DocumentService documents;
    public DocumentFolderService(DocumentService documents){this.documents=documents;}
    public DocumentFolder get(UUID id){var f=em.find(DocumentFolder.class,id);if(f==null || !f.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return f;}
    public List<DocumentFolder> list(UUID parentId){if(parentId!=null)get(parentId);return em.createQuery("from DocumentFolder f where f.userId=:user order by f.name",DocumentFolder.class).setParameter("user",CurrentUser.id()).getResultList().stream().filter(f->Objects.equals(f.parentId,parentId)).toList();}
    public Map<String,Object> detail(UUID id){var folder=get(id);var ancestors=new ArrayList<DocumentFolder>();UUID parent=folder.parentId;while(parent!=null){var item=get(parent);ancestors.addFirst(item);parent=item.parentId;}var result=new LinkedHashMap<String,Object>();result.put("id",folder.id);result.put("name",folder.name);result.put("parentId",folder.parentId);result.put("createdAt",folder.createdAt);result.put("updatedAt",folder.updatedAt);result.put("ancestors",ancestors);return result;}
    public DocumentFolder save(UUID id,Map<String,Object> input){var folder=id==null?new DocumentFolder():get(id);folder.userId=CurrentUser.id();
        if(input.get("parentId")!=null){UUID parent=UUID.fromString(input.get("parentId").toString());for(UUID cursor=parent;cursor!=null;){if(folder.id.equals(cursor))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文件夹不能移入自身或子文件夹");cursor=get(cursor).parentId;}}
        EntityFields.apply(folder,input,"name","parentId");if(folder.name==null || folder.name.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入文件夹名称");if(id==null)em.persist(folder);return folder;
    }
    public void delete(UUID id)throws IOException {
        var folder=get(id);
        for(var child:list(id))delete(child.id);
        var items=em.createQuery("from Document d where d.userId=:user and d.folderId=:folder",Document.class).setParameter("user",CurrentUser.id()).setParameter("folder",id).getResultList();
        for(var document:items)documents.delete(document.id);
        em.remove(folder);
    }
}
