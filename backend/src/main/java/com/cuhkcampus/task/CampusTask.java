package com.cuhkcampus.task;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "tasks")
public class CampusTask {
    @Id public UUID id = UUID.randomUUID();
    public UUID userId;
    public UUID courseId;
    public String taskType = "PERSONAL";
    public String title;
    public String description;
    public String status = "TODO";
    public String priority = "NORMAL";
    public Instant dueAt;
    public Integer estimatedMinutes;
    public String organizer;
    public String category;
    public Instant startsAt;
    public Instant endsAt;
    public String location;
    public String registrationUrl;
    public boolean starred;
    public String sourceType;
    public UUID sourceId;
}
