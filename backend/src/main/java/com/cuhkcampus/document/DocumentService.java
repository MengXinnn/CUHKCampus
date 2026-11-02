package com.cuhkcampus.document;

import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.course.CourseService;
import com.cuhkcampus.course.EntityFields;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import com.cuhkcampus.integration.blackboard.BlackboardHttpClient.DownloadProgress;
import java.util.function.Consumer;
import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.apache.tika.Tika;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.io.*;

@Service
@Transactional
public class DocumentService {
    @PersistenceContext EntityManager em;
    private final Path root;
    private final java.util.concurrent.ConcurrentHashMap<UUID,Object> previewLocks=new java.util.concurrent.ConcurrentHashMap<>();
    private final CourseService courses;
    private final String soffice;
    private final BlackboardApiConnector blackboard;
    public DocumentService(@Value("${campus.storage-root:./data/files}") String root,CourseService courses,
            @Value("${campus.soffice-binary:soffice}") String soffice,BlackboardApiConnector blackboard){
        this.root=Path.of(root).toAbsolutePath();this.courses=courses;this.soffice=soffice;this.blackboard=blackboard;
    }
    public List<Document> list(String filename,UUID courseId,String folderId){
        return em.createQuery("from Document d where d.userId=:user order by d.displayName",Document.class).setParameter("user",CurrentUser.id()).getResultList().stream()
            .filter(d->filename==null || d.displayName.toLowerCase().contains(filename.toLowerCase()))
            .filter(d->courseId==null || courseId.equals(d.courseId))
            .filter(d->folderId==null || (folderId.equals("root")?d.folderId==null:UUID.fromString(folderId).equals(d.folderId))).toList();
    }
    public Document get(UUID id){var d=em.find(Document.class,id);if(d==null || !d.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return d;}
    public Document upload(MultipartFile file,UUID courseId,UUID folderId)throws Exception {
        String filename=file.getOriginalFilename();
        String extension=filename==null || !filename.contains(".")?"":filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(!Set.of("pdf","ppt","pptx","doc","docx","xls","xlsx","txt","md","markdown","java","png","jpg","jpeg","mp4").contains(extension))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"支持 PDF、Office、文本、图片和 MP4 文件");
        if(courseId!=null)courses.get(courseId);
        if(folderId!=null)folder(folderId);
        var d=new Document();d.folderId=folderId;d.userId=CurrentUser.id();d.courseId=courseId;d.displayName=file.getOriginalFilename();
        d.byteSize=file.getSize();d.storageKey=d.id.toString();d.origin="UPLOAD";
        Files.createDirectories(root);
        try(var input=file.getInputStream()){Files.copy(input,root.resolve(d.storageKey));}
        var tika=new Tika();try(var input=Files.newInputStream(root.resolve(d.storageKey))){d.mimeType=tika.detect(input,d.displayName);}
        try(var input=Files.newInputStream(root.resolve(d.storageKey))){var digest=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[8192];int count;while((count=input.read(buf))!=-1)digest.update(buf,0,count);d.sha256=HexFormat.of().formatHex(digest.digest());}
        em.persist(d);return d;
    }
    public Document patch(UUID id,Map<String,Object> input){var d=get(id);if(input.containsKey("displayName") && (input.get("displayName")==null || input.get("displayName").toString().isBlank()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请输入文件名称");if(input.get("courseId")!=null)courses.get(UUID.fromString(input.get("courseId").toString()));if(input.get("folderId")!=null)folder(UUID.fromString(input.get("folderId").toString()));EntityFields.apply(d,input,"displayName","courseId","folderId");return d;}
    private DocumentFolder folder(UUID id){var f=em.find(DocumentFolder.class,id);if(f==null || !f.userId.equals(CurrentUser.id()))throw new ResponseStatusException(HttpStatus.NOT_FOUND);return f;}
    public record ArchiveItem(String name,Path file){}
    public List<ArchiveItem> archive(List<UUID> ids,List<UUID> folderIds){
        var output=new ArrayList<ArchiveItem>();var documents=new HashSet<UUID>();var topNames=new HashSet<String>();
        var roots=new LinkedHashSet<UUID>(folderIds==null?List.of():folderIds);
        for(UUID id:roots){var item=folder(id);boolean nested=false;for(UUID parent=item.parentId;parent!=null;){if(roots.contains(parent)){nested=true;break;}parent=folder(parent).parentId;}if(!nested)archiveFolder(item,archiveName(item.name,topNames)+"/",output,documents);}
        for(UUID id:ids==null?List.<UUID>of():ids){var document=get(id);if(documents.add(id))output.add(new ArchiveItem(archiveName(document.displayName,topNames),localFile(document)));}
        return output;
    }
    private void archiveFolder(DocumentFolder folder,String prefix,List<ArchiveItem> output,Set<UUID> selected){
        output.add(new ArchiveItem(prefix,null));var names=new HashSet<String>();
        var children=em.createQuery("from DocumentFolder f where f.userId=:user and f.parentId=:parent order by f.name",DocumentFolder.class).setParameter("user",CurrentUser.id()).setParameter("parent",folder.id).getResultList();
        for(var child:children)archiveFolder(child,prefix+archiveName(child.name,names)+"/",output,selected);
        var files=em.createQuery("from Document d where d.userId=:user and d.folderId=:folder order by d.displayName",Document.class).setParameter("user",CurrentUser.id()).setParameter("folder",folder.id).getResultList();
        for(var file:files)if(selected.add(file.id))output.add(new ArchiveItem(prefix+archiveName(file.displayName,names),localFile(file)));
    }
    private String archiveName(String displayName,Set<String> names){
        String original=displayName.replace('\\','_').replace('/','_');if(original.equals(".") || original.equals(".."))original="_";String name=original;int suffix=2;
        while(!names.add(name)){int dot=original.lastIndexOf('.');name=dot>0?original.substring(0,dot)+" ("+suffix+")"+original.substring(dot):original+" ("+suffix+")";suffix++;}
        return name;
    }
    public void prepare(UUID userId, UUID id, Consumer<DownloadProgress> progress) {
        var document=em.find(Document.class,id);
        if(document==null || !document.userId.equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(document.storageKey==null) {
            progress.accept(new DownloadProgress(0,document.byteSize));
            blackboard.materialize(userId,document,progress);
        } else progress.accept(new DownloadProgress(document.byteSize,document.byteSize));
    }
    public Path file(UUID id){return localFile(get(id));}
    public String text(UUID id)throws Exception {return readText(CurrentUser.id(),id);}
    public String readText(UUID userId,UUID id) {var d=em.find(Document.class,id);if(d==null || !d.userId.equals(userId))throw new ResponseStatusException(HttpStatus.NOT_FOUND);var tika=new Tika();tika.setMaxStringLength(-1);try(var input=Files.newInputStream(localFile(d,userId))){return tika.parseToString(input);}catch(Exception ex){throw new IllegalStateException("无法读取文档正文",ex);}}
    public Path preview(UUID id) {
        synchronized(previewLocks.computeIfAbsent(id,key -> new Object())) { return renderPreview(id); }
    }
    private Path renderPreview(UUID id) {
        var document=get(id);
        Path original=localFile(document);
        if("application/pdf".equals(document.mimeType))return original;
        String extension=switch(document.mimeType){
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/msword" -> "doc";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"PDF 预览支持 PDF、Word、PowerPoint 和 Excel 文件");
        };
        Path directory=root.resolve("previews").resolve(id.toString());
        Path pdf=directory.resolve("source.pdf");
        if(Files.isRegularFile(pdf))return pdf;
        try {
            Files.createDirectories(directory);
            Path source=directory.resolve("source."+extension);
            if(!Files.isRegularFile(source)) Files.copy(original,source);
            Path profile=Files.createTempDirectory(directory,"render-");
            try {
                Path log=directory.resolve("conversion.log");
                Process process=new ProcessBuilder(soffice,"-env:UserInstallation="+profile.resolve("profile").toUri(),"--headless","--convert-to","pdf","--outdir",profile.toString(),source.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                int exit=process.waitFor();
                Path generated=profile.resolve("source.pdf");
                if(exit!=0 || !Files.isRegularFile(generated))throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"LibreOffice 无法生成 PDF 预览，请检查文件内容（退出码 "+exit+"）");
                Files.move(generated,pdf);
                return pdf;
            } finally {deleteDirectory(profile);}
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Office 预览转换被中断",e);}
        catch(IOException e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"无法生成 Office 预览，请检查 LibreOffice 安装与文件权限",e);}
    }
    private void deleteDirectory(Path directory)throws IOException {
        if(!Files.exists(directory))return;
        try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
    public void delete(UUID id)throws IOException {var d=get(id);if(d.storageKey!=null)Files.deleteIfExists(root.resolve(d.storageKey));deleteDirectory(root.resolve("previews").resolve(id.toString()));em.remove(d);}

    private Path localFile(Document document){return localFile(document,CurrentUser.id());}
    private Path localFile(Document document,UUID userId){
        if(document.storageKey==null) {
            if(!"BLACKBOARD".equals(document.origin))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"文档内容不可用");
            blackboard.materialize(userId,document);
            em.refresh(document);
        }
        return root.resolve(document.storageKey);
    }

}
