package com.example.digitalsignchecker.application.command;

import com.example.digitalsignchecker.domain.enums.DocumentType;

import java.util.UUID;

public record VerifyDocumentCommand(
        UUID uuid,
        DocumentType type,
        byte[] data
) {}
