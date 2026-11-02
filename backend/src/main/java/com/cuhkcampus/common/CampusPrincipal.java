package com.cuhkcampus.common;

import java.io.Serializable;
import java.security.Principal;
import java.util.UUID;

public record CampusPrincipal(UUID id, String email) implements Principal, Serializable {
    @Override public String getName() { return email; }
}
