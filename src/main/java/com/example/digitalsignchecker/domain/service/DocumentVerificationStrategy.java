package com.example.digitalsignchecker.domain.service;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import org.springframework.stereotype.Component;

@Component
public interface DocumentVerificationStrategy {
    VerifyResultDTO verifyDocument(byte[] document);
}
