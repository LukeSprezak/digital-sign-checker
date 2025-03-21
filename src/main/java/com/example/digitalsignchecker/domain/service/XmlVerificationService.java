package com.example.digitalsignchecker.domain.service;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import org.springframework.stereotype.Service;

@Service
public class XmlVerificationService implements DocumentVerificationStrategy {

    @Override
    public VerifyResultDTO verifyDocument(byte[] document) {
        return VerifyResultDTO.fromVerification(false, "Valid PDF signature");
    }
}
