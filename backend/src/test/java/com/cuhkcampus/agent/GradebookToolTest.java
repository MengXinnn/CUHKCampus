package com.cuhkcampus.agent;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GradebookToolTest {
 @Test void exposesGradebookAndPreservesZeroAndUngradedScores(){
  var user=UUID.randomUUID();var course=UUID.randomUUID();var bb=mock(BlackboardApiConnector.class);
  when(bb.grades(user,course)).thenReturn(List.of(Map.of("id","zero","grade",Map.of("score",0)),Map.of("id","ungraded")));
  var tools=new CampusTools(user,null,null,null,null,bb);
  var callback=Arrays.stream(ToolCallbacks.from(tools)).filter(t->t.getToolDefinition().name().equals("get_course_gradebook")).findFirst().orElseThrow();
  String json=callback.call("{\"courseId\":\""+course+"\"}");
  assertTrue(json.contains("\"score\":0"));assertTrue(json.contains("ungraded"));verify(bb).grades(user,course);
 }
}
