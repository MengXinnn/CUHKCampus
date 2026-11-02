package com.cuhkcampus.document;

import jakarta.persistence.*;
import java.util.*;
import java.time.*;

@Entity
@Table(name = "documents")
public class Document {
    @Id public UUID id = UUID.randomUUID();
    public UUID userId;
    public UUID folderId;
    public Instant createdAt=Instant.now();
    public Instant updatedAt=createdAt;
    @PreUpdate void updateTime(){updatedAt=Instant.now();}
    public UUID courseId;
    public UUID contentNodeId;
    public UUID mailMessageId;
    public String externalId;
    public String displayName;
    public String mimeType;
    public long byteSize;
    public String sha256;
    public String storageKey;
    public String origin;
}
