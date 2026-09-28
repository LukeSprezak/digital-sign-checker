package com.example.digitalsignchecker.application.dto;

import java.util.List;

public record VerificationOutcome(
        boolean verified,
        String message,
        List<SignatureDTO> signatures
) {}
