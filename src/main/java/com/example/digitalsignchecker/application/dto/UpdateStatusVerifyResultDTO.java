package com.example.digitalsignchecker.application.dto;

import com.example.digitalsignchecker.domain.enums.VerificationStatus;

public record UpdateStatusVerifyResultDTO(
        VerificationStatus status,
        boolean verified,
        String message
) {}
