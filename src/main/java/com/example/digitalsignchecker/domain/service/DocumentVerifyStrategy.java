package com.example.digitalsignchecker.domain.service;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import org.springframework.stereotype.Component;

@Component
public interface DocumentVerifyStrategy {
    VerifyResultDTO verifyDocument(byte[] document);
}
