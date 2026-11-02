package com.cuhkcampus.document;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/v1/document-folders")
public class DocumentFolderController {
    private final DocumentFolderService service;
    public DocumentFolderController(DocumentFolderService service){this.service=service;}
    @GetMapping public List<DocumentFolder> list(@RequestParam(required=false)String parentId){return service.list(parentId==null || parentId.equals("root")?null:UUID.fromString(parentId));}
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable UUID id){return service.detail(id);}
    @PostMapping public DocumentFolder create(@RequestBody Map<String,Object> input){return service.save(null,input);}
    @PatchMapping("/{id}") public DocumentFolder update(@PathVariable UUID id,@RequestBody Map<String,Object> input){return service.save(id,input);}
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id)throws Exception{service.delete(id);}
}
