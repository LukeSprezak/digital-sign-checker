package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class XmlVerifyService implements DocumentVerifyStrategy {

    @Override
    public VerifyResultDTO verifyDocument(byte[] document) {
        return VerifyResultDTO.fromVerification(false, "XML signature verification is not supported yet.", List.of());
    }
}
