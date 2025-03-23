package com.example.digitalsignchecker.application.dto;

import com.example.digitalsignchecker.domain.model.Signature;

import java.time.Instant;

public record SignatureDTO(
        String signerName,
        String certificateIssuer,
        Instant signingTime
) {
    public static SignatureDTO fromEntity(Signature signature) {
        return new SignatureDTO(
                signature.getSignerName(),
                signature.getCertificateIssuer(),
                signature.getSigningTime()
        );
    }
}
