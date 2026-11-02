package com.cuhkcampus.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping({"/", "/courses", "/courses/{id}", "/documents", "/documents/{id}", "/mail", "/mail/{id}", "/tasks", "/calendar", "/copilot", "/copilot/{id}", "/settings"})
    String page() { return "forward:/index.html"; }
}
