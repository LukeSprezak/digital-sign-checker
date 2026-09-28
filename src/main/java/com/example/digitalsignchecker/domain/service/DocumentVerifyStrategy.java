package com.example.digitalsignchecker.domain.service;

import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import com.example.digitalsignchecker.domain.enums.DocumentType;

public interface DocumentVerifyStrategy {
    DocumentType supportedType();
    VerificationOutcome verifyDocument(byte[] document);
}
