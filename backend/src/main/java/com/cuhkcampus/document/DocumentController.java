package com.cuhkcampus.document;
import org.springframework.web.bind.annotation.*;
import com.cuhkcampus.common.CurrentUser;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.*;
import java.util.zip.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {
    private final DocumentService service;
    private final SlidePreviewService slides;
    public DocumentController(DocumentService service,SlidePreviewService slides){this.service=service;this.slides=slides;}
    @GetMapping public List<Document> list(@RequestParam(required=false)String filename,@RequestParam(required=false)UUID courseId,@RequestParam(required=false)String folderId){return service.list(filename,courseId,folderId);}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Document upload(@RequestPart MultipartFile file,@RequestParam(required=false)UUID courseId,@RequestParam(required=false)UUID folderId)throws Exception{return service.upload(file,courseId,folderId);}
    @GetMapping("/{id}") public Document get(@PathVariable UUID id){return service.get(id);}
    @PatchMapping("/{id}") public Document patch(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.patch(id,body);}
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id)throws Exception{service.delete(id);}
    @GetMapping(value="/{id}/prepare",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter prepare(@PathVariable UUID id) {
        service.get(id);
        UUID userId=CurrentUser.id();
        var emitter=new SseEmitter(0L);
        Thread.ofVirtual().start(() -> {
            try {
                service.prepare(userId,id,progress -> send(emitter,"progress",progress));
                send(emitter,"ready",Map.of("id",id));
                emitter.complete();
            } catch(Exception error) {
                String message=error instanceof ResponseStatusException status ? status.getReason() : "文件下载失败";
                try {send(emitter,"failed",Map.of("message",message));emitter.complete();}
                catch(Exception disconnected) {emitter.completeWithError(error);}
            }
        });
        return emitter;
    }
    private void send(SseEmitter emitter,String event,Object value) {
        try {emitter.send(SseEmitter.event().name(event).data(value));}
        catch(IOException error) {throw new UncheckedIOException(error);}
    }
    @GetMapping("/{id}/file") public ResponseEntity<Resource> file(@PathVariable UUID id,@RequestParam(defaultValue="false")boolean download){var d=service.get(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.mimeType)).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.builder(download?"attachment":"inline").filename(d.displayName,StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(service.file(id)));}
    @GetMapping(value="/{id}/slides/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter slides(@PathVariable UUID id){var document=service.get(id);return slides.stream(id,service.file(id),document.mimeType);}
    @GetMapping("/{id}/slides/thumbnail") public ResponseEntity<byte[]> thumbnail(@PathVariable UUID id,@RequestParam int page)throws IOException{service.get(id);return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(slides.thumbnail(id,page));}
    @GetMapping("/{id}/preview") public ResponseEntity<Resource> preview(@PathVariable UUID id,@RequestParam(required=false)Integer page){
        var document=service.get(id);
        String filename=document.displayName.replaceFirst("\\.[^.]+$","")+".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.inline().filename(filename,StandardCharsets.UTF_8).build().toString()).body(new FileSystemResource(page==null?service.preview(id):slides.page(id,page)));
    }
    public record DownloadSelection(List<UUID> ids,List<UUID> folderIds){}
    @PostMapping("/download") public ResponseEntity<StreamingResponseBody> download(@RequestBody DownloadSelection selection){
        var entries=service.archive(selection.ids(),selection.folderIds());
        StreamingResponseBody body=output->{
            try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)){
                for(var entry:entries){zip.putNextEntry(new ZipEntry(entry.name()));if(entry.file()!=null)Files.copy(entry.file(),zip);zip.closeEntry();}
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=documents.zip").body(body);
    }
    @GetMapping("/{id}/text") public Map<String,String> text(@PathVariable UUID id)throws Exception{return Map.of("content",service.text(id));}
}
