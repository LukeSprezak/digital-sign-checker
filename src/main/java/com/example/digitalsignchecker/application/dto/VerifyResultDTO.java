package com.example.digitalsignchecker.application.dto;

import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.model.Signature;
import com.example.digitalsignchecker.domain.model.VerifyResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record VerifyResultDTO(
        UUID uuid,
        VerifyStatus status,
        boolean verified,
        String message,
        Instant verificationTime,
        List<SignatureDTO> signatures
) {
    public static VerifyResultDTO fromEntity(VerifyResult entity, List<Signature> signatures) {
        List<SignatureDTO> signatureDTOs = signatures.stream()
                .map(SignatureDTO::fromEntity)
                .collect(Collectors.toList());

        return new VerifyResultDTO(
                entity.getUuid(),
                entity.getStatus(),
                entity.isVerified(),
                entity.getMessage(),
                entity.getVerificationTime(),
                signatureDTOs
        );
    }
}
