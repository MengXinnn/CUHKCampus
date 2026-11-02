package com.cuhkcampus.document;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="document_folders")
public class DocumentFolder {
    @Id public UUID id=UUID.randomUUID();
    public UUID userId;
    public UUID parentId;
    public String name;
    public Instant createdAt=Instant.now();
    public Instant updatedAt=createdAt;
    @PreUpdate void updateTime(){updatedAt=Instant.now();}
}
