package com.cuhkcampus.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.*;

@Service
public class SlidePreviewService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SlidePreviewService.class);
    private final Path root;
    private final LibreOfficeSlides office;
    private final Map<UUID,Job> jobs=new ConcurrentHashMap<>();
    public SlidePreviewService(@Value("${campus.storage-root:./data/files}")String root,LibreOfficeSlides office){this.root=Path.of(root).toAbsolutePath();this.office=office;}
    private Path directory(UUID id){return root.resolve("previews").resolve(id.toString());}
    public Path page(UUID id,int page){
        if(page<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"页码应从 1 开始");
        Path file=directory(id).resolve("page-"+page+".pdf");
        if(!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.CONFLICT,"该页尚未生成");
        return file;
    }
    public byte[] thumbnail(UUID id,int page) throws IOException {
        try(var pdf=org.apache.pdfbox.Loader.loadPDF(page(id,page).toFile());var bytes=new ByteArrayOutputStream()) {
            var image=new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImage(0,86f/pdf.getPage(0).getMediaBox().getWidth());
            javax.imageio.ImageIO.write(image,"png",bytes);return bytes.toByteArray();
        }
    }
    public SseEmitter stream(UUID id,Path source,String mime){
        if(!Set.of("application/vnd.ms-powerpoint","application/vnd.openxmlformats-officedocument.presentationml.presentation").contains(mime))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"逐页预览仅支持 PPT 和 PPTX");
        Job job=jobs.computeIfAbsent(id,key -> new Job(id,source,mime));
        var emitter=new SseEmitter(0L);
        emitter.onCompletion(()->job.remove(emitter));
        emitter.onTimeout(()->job.remove(emitter));
        emitter.onError(error->job.remove(emitter));
        job.subscribe(emitter);return emitter;
    }
    private class Job {
        final UUID id;final Path source;final String mime;
        final List<SseEmitter> listeners=new ArrayList<>();final SortedSet<Integer> ready=new TreeSet<>();
        int total;boolean started,done;String error;
        Job(UUID id,Path source,String mime){this.id=id;this.source=source;this.mime=mime;}
        synchronized void remove(SseEmitter emitter){listeners.remove(emitter);}
        synchronized void subscribe(SseEmitter emitter){
            if(total>0)send(emitter,"info",Map.of("pages",total));
            for(int page:ready)send(emitter,"page",Map.of("page",page));
            if(done){send(emitter,"done",Map.of("pages",total));emitter.complete();return;}
            if(error!=null){send(emitter,"failed",Map.of("message",error));emitter.complete();return;}
            listeners.add(emitter);
            if(!started){started=true;Thread.ofVirtual().start(this::run);}
        }
        void send(SseEmitter emitter,String event,Object value){
            try {emitter.send(SseEmitter.event().name(event).data(value));}
            catch(IOException failure){listeners.remove(emitter);emitter.completeWithError(failure);}
        }
        synchronized void publish(String event,Object value){for(var emitter:List.copyOf(listeners))send(emitter,event,value);}
        synchronized void count(int value){total=value;publish("info",Map.of("pages",total));}
        synchronized void ready(int page){ready.add(page);publish("page",Map.of("page",page));}
        void run(){
            try {
                Path directory=directory(id);Files.createDirectories(directory);
                Path manifest=directory.resolve("pages.txt");
                if(Files.isRegularFile(manifest)) {
                    count(Integer.parseInt(Files.readString(manifest)));
                    for(int page=1;page<=total;page++)if(Files.isRegularFile(directory.resolve("page-"+page+".pdf")))ready(page);
                    if(ready.size()==total){finish();return;}
                }
                Path full=directory.resolve("source.pdf");
                if(Files.isRegularFile(full)) {
                    try(var pdf=org.apache.pdfbox.Loader.loadPDF(full.toFile())) {
                        count(pdf.getNumberOfPages());Files.writeString(manifest,String.valueOf(total));
                        for(int page=1;page<=total;page++) {
                            Path target=directory.resolve("page-"+page+".pdf");
                            if(!Files.isRegularFile(target))try(var single=new org.apache.pdfbox.pdmodel.PDDocument()) {
                                single.importPage(pdf.getPage(page-1));Path temporary=directory.resolve("page-"+page+".pending.pdf");single.save(temporary.toFile());Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                            }
                            ready(page);
                        }
                    }
                } else {
                    Path input=directory.resolve("source."+(mime.endsWith("presentation")?"pptx":"ppt"));
                    if(!Files.isRegularFile(input))Files.copy(source,input);
                    office.render(input,directory,value->{count(value);try{Files.writeString(manifest,String.valueOf(value));}catch(IOException failure){throw new UncheckedIOException(failure);}},this::ready);
                }
                finish();
            } catch(Exception failure){
                log.error("Slide preview failed: document={}",id,failure);
                synchronized(this){error="逐页预览生成失败："+failure.getMessage();publish("failed",Map.of("message",error));for(var emitter:List.copyOf(listeners))emitter.complete();listeners.clear();}
            } finally {jobs.remove(id,this);}
        }
        synchronized void finish(){done=true;publish("done",Map.of("pages",total));for(var emitter:List.copyOf(listeners))emitter.complete();listeners.clear();}
    }
}
