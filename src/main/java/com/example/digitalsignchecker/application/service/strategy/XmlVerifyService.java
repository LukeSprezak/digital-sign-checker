package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class XmlVerifyService implements DocumentVerifyStrategy {

    @Override
    public DocumentType supportedType() {
        return DocumentType.XML;
    }

    @Override
    public VerificationOutcome verifyDocument(byte[] document) {
        return new VerificationOutcome(false, "XML signature verification is not supported yet.", List.of());
    }
}
