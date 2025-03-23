package com.example.digitalsignchecker.domain.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class Signature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    private String signerName;

    private String certificateIssuer;

    private Instant signingTime;

    public Signature() {}

    public Signature(Document finalDocument, String signerName, String certificateIssuer, Instant signingTime) {
        this.document = finalDocument;
        this.signerName = signerName;
        this.certificateIssuer = certificateIssuer;
        this.signingTime = signingTime;
    }

    public Long getId() {
        return id;
    }

    public Document getDocument() {
        return document;
    }

    public String getSignerName() {
        return signerName;
    }

    public String getCertificateIssuer() {
        return certificateIssuer;
    }

    public Instant getSigningTime() {
        return signingTime;
    }
}
