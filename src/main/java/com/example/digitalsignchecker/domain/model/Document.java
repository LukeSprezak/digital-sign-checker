package com.example.digitalsignchecker.domain.model;

import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

import java.time.Instant;
import java.util.UUID;

@Entity
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uuid;

    @Column(nullable = false)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DocumentStatus status;

    @Lob
    @Basic(fetch = FetchType.EAGER)
    @Column(columnDefinition = "LONGBLOB", nullable = false)
    private byte[] content;

    @Column(nullable = false, updatable = false)
    private Instant uploadedAt;

    public Document() {}

    public Document(String filename, DocumentType type, byte[] content) {
        this.uuid = UUID.randomUUID();
        this.filename = filename;
        this.type = type;
        this.status = DocumentStatus.PENDING;
        this.content = content;
        this.uploadedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getFilename() {
        return filename;
    }

    public DocumentType getType() {
        return type;
    }

    public byte[] getContent() {
        return content;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
