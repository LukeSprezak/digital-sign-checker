package com.example.digitalsignchecker.domain.service;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import org.springframework.stereotype.Service;

@Service
public class XmlVerifyService implements DocumentVerifyStrategy {

    @Override
    public VerifyResultDTO verifyDocument(byte[] document) {
        return null;
    }
}
