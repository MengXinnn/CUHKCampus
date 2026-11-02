package com.cuhkcampus.course;

import com.cuhkcampus.common.CurrentUser;
import com.cuhkcampus.integration.blackboard.BlackboardApiConnector;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/courses")
public class GradebookController {
    private final BlackboardApiConnector blackboard;
    public GradebookController(BlackboardApiConnector blackboard){this.blackboard=blackboard;}
    @GetMapping("/{id}/grades") public List<Map<String,Object>> grades(@PathVariable UUID id){return blackboard.grades(CurrentUser.id(),id);}
}
