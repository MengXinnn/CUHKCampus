package com.cuhkcampus.integration.outlook;

import com.cuhkcampus.common.CurrentUser;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class OutlookActionsController {
    private final OutlookSyncService outlook;
    public OutlookActionsController(OutlookSyncService outlook) {this.outlook=outlook;}
    @GetMapping("/mail/search")
    public List<Map<String,Object>> search(@RequestParam String query,@RequestParam(defaultValue="50")int limit) {return outlook.search(CurrentUser.id(),query,limit);}
    @PostMapping("/calendar/events/{id}/outlook")
    public Map<String,Object> calendar(@PathVariable UUID id) {return outlook.createCalendarEvent(CurrentUser.id(),id);}
}
