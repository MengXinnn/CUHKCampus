package com.cuhkcampus.mail;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mail_messages")
public class MailMessage {
    @Id public UUID id = UUID.randomUUID();
    public UUID userId;
    public UUID folderId;
    public UUID courseId;
    public String externalId;
    public String subject;
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> sender;
    public Instant receivedAt;
    public String bodyHtml;
    public String bodyText;
    public String webLink;
    public String category;
    public String importance;
    public boolean isRead;
    public String summary;
}
