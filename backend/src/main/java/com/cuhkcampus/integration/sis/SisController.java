package com.cuhkcampus.integration.sis;
import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/v1/connections/blackboard")
public class SisController {
 private final SisService service;private final BlackboardApiConnector blackboard;
 public SisController(SisService service,BlackboardApiConnector blackboard){this.service=service;this.blackboard=blackboard;}
 @PostMapping("/sync") public Map<String,Object> sync(){var user=CurrentUser.id();var courses=blackboard.courses(user);var timetable=service.sync(user);return Map.of("courses",courses.size(),"events",timetable.get("count"));}
}
