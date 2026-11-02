package com.cuhkcampus.course;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "announcements")
public class Announcement {
    @Id public UUID id = UUID.randomUUID();
    public UUID courseId;
    public String externalId;
    public String title;
    public String bodyHtml;
    public String bodyText;
    public Instant publishedAt;
    public boolean isRead;
}
