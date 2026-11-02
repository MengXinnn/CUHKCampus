package com.cuhkcampus.common;

import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}
    public static UUID id() {
        return ((CampusPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).id();
    }
}
