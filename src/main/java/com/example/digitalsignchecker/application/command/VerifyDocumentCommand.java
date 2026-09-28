package com.example.digitalsignchecker.application.command;

import com.example.digitalsignchecker.domain.enums.DocumentType;

public record VerifyDocumentCommand(
        Long documentId,
        DocumentType type,
        byte[] data
) {}
