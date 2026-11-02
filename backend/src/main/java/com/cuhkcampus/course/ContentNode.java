package com.cuhkcampus.course;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "content_nodes")
public class ContentNode {
    @Id public UUID id = UUID.randomUUID();
    public UUID courseId;
    public UUID parentId;
    public String externalId;
    public String nodeType;
    public String title;
    public String bodyHtml;
    public String bodyText;
    public Instant dueAt;
    public String sourceUrl;
    public int position;
}
