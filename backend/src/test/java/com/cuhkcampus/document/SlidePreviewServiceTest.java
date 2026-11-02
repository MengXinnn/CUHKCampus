package com.cuhkcampus.document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class SlidePreviewServiceTest {
 @TempDir Path root;
 @RestController static class Endpoint {
  final SlidePreviewService service;final Path source;final UUID id;
  Endpoint(SlidePreviewService service,Path source,UUID id){this.service=service;this.source=source;this.id=id;}
  @GetMapping(value="/stream",produces="text/event-stream") SseEmitter stream(){return service.stream(id,source,"application/vnd.openxmlformats-officedocument.presentationml.presentation");}
 }
 private void pdf(Path file)throws Exception{try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());pdf.save(file.toFile());}}
 @Test void streamsBeforeCompletionSharesJobAndReusesCache()throws Exception{
  var converter=mock(LibreOfficeSlides.class);var first=new CountDownLatch(1);var proceed=new CountDownLatch(1);
  doAnswer(call->{Path directory=call.getArgument(1);IntConsumer count=call.getArgument(2),ready=call.getArgument(3);
   count.accept(2);pdf(directory.resolve("page-1.pdf"));ready.accept(1);first.countDown();
   assertTrue(proceed.await(5,TimeUnit.SECONDS));pdf(directory.resolve("page-2.pdf"));ready.accept(2);return null;
  }).when(converter).render(any(),any(),any(),any());
  Path source=root.resolve("input.pptx");Files.writeString(source,"fixture");UUID id=UUID.randomUUID();
  var service=new SlidePreviewService(root.toString(),converter);var mvc=MockMvcBuilders.standaloneSetup(new Endpoint(service,source,id)).build();
  var request=mvc.perform(get("/stream")).andReturn();assertTrue(first.await(3,TimeUnit.SECONDS));
  assertTrue(request.getResponse().getContentAsString().contains("event:page"));assertTrue(Files.isRegularFile(service.page(id,1)));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.page(id,2));
  var joined=mvc.perform(get("/stream")).andReturn();assertTrue(joined.getResponse().getContentAsString().contains("event:page"));
  proceed.countDown();request.getAsyncResult(3000);joined.getAsyncResult(3000);
  String body=mvc.perform(asyncDispatch(request)).andReturn().getResponse().getContentAsString();
  assertTrue(body.indexOf("event:info")<body.indexOf("event:page"));assertTrue(body.contains("event:done"));assertTrue(service.thumbnail(id,1).length>0);
  var cached=mvc.perform(get("/stream")).andReturn();cached.getAsyncResult(3000);
  assertTrue(mvc.perform(asyncDispatch(cached)).andReturn().getResponse().getContentAsString().contains("event:done"));
  verify(converter,times(1)).render(any(),any(),any(),any());
 }
 @Test void failureIsStreamedAsError()throws Exception{
  var converter=mock(LibreOfficeSlides.class);doThrow(new IllegalStateException("conversion failed")).when(converter).render(any(),any(),any(),any());
  Path source=root.resolve("input.pptx");Files.writeString(source,"fixture");
  var service=new SlidePreviewService(root.toString(),converter);var mvc=MockMvcBuilders.standaloneSetup(new Endpoint(service,source,UUID.randomUUID())).build();
  var request=mvc.perform(get("/stream")).andReturn();request.getAsyncResult(3000);
  String body=mvc.perform(asyncDispatch(request)).andReturn().getResponse().getContentAsString();assertTrue(body.contains("event:failed"));assertFalse(body.contains("event:done"));
 }
}
