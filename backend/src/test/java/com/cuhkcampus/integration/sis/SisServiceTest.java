package com.cuhkcampus.integration.sis;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class SisServiceTest {
 @Test void expandsOnlyScheduledWeekdaysWithinSemesterInShanghaiTime(){
  var events=SisService.parse("<table><tr><td class='PAGROUPDIVIDER'>CSC 1003</td></tr></table><table id='CLASS_MTG_VW$scroll$0'><tr><th>标题</th></tr><tr><td>123</td><td>L01</td><td>讲演</td><td>星期一星期三 4:00PM - 5:20PM</td><td>A101</td><td>Teacher</td><td>09/07/2026 - 09/16/2026</td></tr></table>");
  assertEquals(4,events.size());assertEquals("CSC 1003 · L01",events.getFirst().get("title"));assertEquals("A101",events.getFirst().get("location"));assertEquals(Instant.parse("2026-09-07T08:00:00Z"),events.getFirst().get("startsAt"));assertEquals(Instant.parse("2026-09-16T09:20:00Z"),events.getLast().get("endsAt"));
 }
}
