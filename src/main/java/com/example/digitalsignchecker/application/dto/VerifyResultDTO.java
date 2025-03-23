package com.example.digitalsignchecker.application.dto;

import com.example.digitalsignchecker.domain.enums.VerificationStatus;
import com.example.digitalsignchecker.domain.model.VerifyResult;

import java.time.Instant;
import java.util.UUID;

public record VerifyResultDTO(
        UUID uuid,
        VerificationStatus status,
        boolean verified,
        String message,
        Instant verificationTime,
        String signerName,
        String certificateIssuer,
        Instant signingTime
) {
    public static VerifyResultDTO fromEntity(VerifyResult entity) {
        return new VerifyResultDTO(
                entity.getUuid(),
                entity.getStatus(),
                entity.isVerified(),
                entity.getMessage(),
                entity.getVerificationTime(),
                null,
                null,
                null
        );
    }

    public static VerifyResultDTO fromVerification(
            boolean verified,
            String message,
            String signerName,
            String certificateIssuer,
            Instant signingTime
    ) {
        return new VerifyResultDTO(
                UUID.randomUUID(),
                verified ? VerificationStatus.COMPLETED : VerificationStatus.ERROR,
                verified,
                message,
                Instant.now(),
                signerName,
                certificateIssuer,
                signingTime
        );
    }
}
