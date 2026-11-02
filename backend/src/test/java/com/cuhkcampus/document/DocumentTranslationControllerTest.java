package com.cuhkcampus.document;
import com.cuhkcampus.agent.AiFactory;
import com.cuhkcampus.common.CampusPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class DocumentTranslationControllerTest {
 @Test void sendsPageTextAndImageTogetherWithoutReadingWholeDocument()throws Exception{
  UUID user=UUID.randomUUID(),id=UUID.randomUUID();var documents=mock(DocumentService.class);var factory=mock(AiFactory.class);var model=mock(ChatModel.class);var prompt=new AtomicReference<Prompt>();
  when(model.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
  when(model.stream(any(Prompt.class))).thenAnswer(call->{prompt.set(call.getArgument(0));return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("译文")))));});
  when(factory.translationClient(user)).thenReturn(ChatClient.builder(model).build());
  SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new CampusPrincipal(user,"test@example.invalid"),null,List.of()));
  try{
   var mvc=MockMvcBuilders.standaloneSetup(new DocumentTranslationController(documents,factory)).build();
   var result=mvc.perform(post("/api/v1/documents/"+id+"/translate").contentType("application/json").content("{\"targetLanguage\":\"中文\",\"image\":\"data:image/jpeg;base64,AQID\",\"sourceText\":\"Current page text\"}")).andReturn();
   result.getAsyncResult(3000);assertTrue(mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString().contains("event:done"));
   var message=(UserMessage)prompt.get().getInstructions().stream().filter(m->m instanceof UserMessage).findFirst().orElseThrow();
   assertTrue(message.getText().contains("Current page text"));assertArrayEquals(new byte[]{1,2,3},message.getMedia().getFirst().getDataAsByteArray());
   verify(documents).get(id);verify(documents,never()).readText(any(),any());
  }finally{SecurityContextHolder.clearContext();}
 }
}
