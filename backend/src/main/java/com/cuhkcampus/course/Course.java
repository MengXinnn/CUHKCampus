package com.cuhkcampus.course;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "courses")
public class Course {
    @Id public UUID id = UUID.randomUUID();
    public UUID userId;
    public String externalId;
    public String code;
    public String name;
    public String customName;
    public String term;
    public String teacher;
    public String color = "#D6E0CF";
    public boolean archived;
    public String note;
    public Instant lastSyncedAt;
}
