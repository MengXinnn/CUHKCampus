package com.cuhkcampus.calendar;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "course_meeting_rules")
public class CourseMeetingRule {
    @Id public UUID id = UUID.randomUUID();
    public UUID courseId;
    public int weekday;
    public LocalTime startsTime;
    public LocalTime endsTime;
    public LocalDate validFrom;
    public LocalDate validTo;
    public String location;
}
