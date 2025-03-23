package com.example.digitalsignchecker.domain.model;

import jakarta.persistence.*;

@Entity
public class Signature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(nullable = false)
    private String signerName;

    @Column(nullable = false)
    private String certificateIssuer;

    @Column(nullable = false)
    private String signingTime;

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

    public String getSigningTime() {
        return signingTime;
    }
}

