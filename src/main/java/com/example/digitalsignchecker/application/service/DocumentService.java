package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.DocumentNotFoundException;
import com.example.digitalsignchecker.domain.exception.VerificationNotFoundException;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.Signature;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.SignatureRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class DocumentService {

    private static final String PDF_EXTENSION = ".pdf";
    private static final String XML_EXTENSION = ".xml";
    private static final String UTF8_BOM = "ï»¿";

    private final DocumentRepository documentRepository;
    private final VerifyRepository verificationRepository;
    private final SignatureRepository signatureRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentService(
            DocumentRepository documentRepository,
            VerifyRepository verificationRepository,
            SignatureRepository signatureRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.documentRepository = documentRepository;
        this.verificationRepository = verificationRepository;
        this.signatureRepository = signatureRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public VerifyResultDTO getVerifyResult(UUID uuid) {

        Document document = documentRepository.findByUuid(uuid)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

        VerifyResult verifyResult = verificationRepository.findFirstByDocumentOrderByIdDesc(document)
                .orElseThrow(() -> new VerificationNotFoundException("Verification result not found"));

        List<Signature> signatures = signatureRepository.findByDocument(document);

        return VerifyResultDTO.fromEntity(verifyResult, signatures);
    }

    @Transactional
    public URI uploadDocument(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        byte[] content = file.getBytes();
        Document document = documentRepository.save(new Document(
                file.getOriginalFilename(),
                determinationDocumentType(file.getOriginalFilename(), content),
                content
        ));

        // Created in the upload transaction so the status link resolves right after the response.
        verificationRepository.save(new VerifyResult(document, VerifyStatus.PENDING, false, "Begin processing"));

        eventPublisher.publishEvent(new VerifyDocumentCommand(
                document.getUuid(),
                document.getType(),
                document.getContent()
        ));

        return URI.create("/api/documents/status/" + document.getUuid());
    }

    private DocumentType determinationDocumentType(String filename, byte[] content) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename is required.");
        }

        int extensionIndex = filename.lastIndexOf('.');
        if (extensionIndex < 0) {
            throw new IllegalArgumentException("Unsupported document type: " + filename);
        }

        DocumentType type = switch (filename.substring(extensionIndex).toLowerCase()) {
            case PDF_EXTENSION -> DocumentType.PDF;
            case XML_EXTENSION -> DocumentType.XML;
            default -> throw new IllegalArgumentException("Unsupported document type: " + filename);
        };

        if (!contentMatchesType(type, content)) {
            throw new IllegalArgumentException("File content does not match its extension: " + filename);
        }

        return type;
    }

    private boolean contentMatchesType(DocumentType type, byte[] content) {
        // PDF readers accept a header anywhere in the first 1024 bytes.
        String head = new String(content, 0, Math.min(content.length, 1024), StandardCharsets.ISO_8859_1);

        return switch (type) {
            case PDF -> head.contains("%PDF-");
            case XML -> head.replaceFirst("^" + UTF8_BOM, "").stripLeading().startsWith("<");
            case UNKNOWN -> false;
        };
    }
}
