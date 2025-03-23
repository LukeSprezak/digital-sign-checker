package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import org.springframework.stereotype.Service;

@Service
public class PdfVerifyService implements DocumentVerifyStrategy {

    @Override
    public VerifyResultDTO verifyDocument(byte[] document) {
        return null;
    }
}
