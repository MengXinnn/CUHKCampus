package com.cuhkcampus.calendar;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {
    @Id public UUID id = UUID.randomUUID();
    public UUID userId;
    public UUID courseId;
    public UUID taskId;
    public UUID meetingRuleId;
    public String eventType = "PERSONAL";
    public String title;
    public Instant startsAt;
    public Instant endsAt;
    public boolean allDay;
    public String location;
}
