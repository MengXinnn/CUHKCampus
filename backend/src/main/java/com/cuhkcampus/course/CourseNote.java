package com.cuhkcampus.course;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "course_notes")
public class CourseNote {
    @Id public UUID id = UUID.randomUUID();
    public UUID userId;
    public UUID courseId;
    public UUID documentId;
    public String title;
    public String markdown;
}
