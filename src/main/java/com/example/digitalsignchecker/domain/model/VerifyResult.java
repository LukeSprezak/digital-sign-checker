package com.example.digitalsignchecker.domain.model;

import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
public class VerifyResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uuid;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VerifyStatus status;

    @Column(nullable = false)
    private boolean verified;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, updatable = false)
    private Instant verificationTime;

    public VerifyResult() {}

    public VerifyResult(Document document, VerifyStatus status, boolean verified, String message) {
        this.uuid = UUID.randomUUID();
        this.document = document;
        this.status = status;
        this.verified = verified;
        this.message = message;
        this.verificationTime = Instant.now();
    }

    public void updateStatus(VerifyStatus status, boolean verified, String message) {
        this.status = status;
        this.verified = verified;
        this.message = message;
    }

    @PrePersist
    public void generateUuidIfNull() {
        if (this.uuid == null) {
            this.uuid = UUID.randomUUID();
        }
        if (this.verificationTime == null) {
            this.verificationTime = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getUuid() {
        return uuid;
    }

    public Document getDocument() {
        return document;
    }

    public VerifyStatus getStatus() {
        return status;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getMessage() {
        return message;
    }

    public Instant getVerificationTime() {
        return verificationTime;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public void setStatus(VerifyStatus status) {
        this.status = status;
    }
}
