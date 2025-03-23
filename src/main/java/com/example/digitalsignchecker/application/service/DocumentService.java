package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.command.handler.VerifyDocumentHandler;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.DocumentNotFoundException;
import com.example.digitalsignchecker.domain.exception.VerificationNotFoundException;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class DocumentService {

    private static final String PDF_EXTENSION = ".pdf";
    private static final String XML_EXTENSION = ".xml";

    private final DocumentRepository documentRepository;
    private final VerifyRepository verificationRepository;
    private final VerifyDocumentHandler verifyDocumentHandler;

    public DocumentService(
            DocumentRepository documentRepository,
            VerifyRepository verificationRepository,
            VerifyDocumentHandler verifyDocumentHandler
    ) {
        this.documentRepository = documentRepository;
        this.verificationRepository = verificationRepository;
        this.verifyDocumentHandler = verifyDocumentHandler;
    }

    @Transactional()
    public VerifyResultDTO getVerifyResult(UUID uuid) {

        Document document = documentRepository.findByUuid(uuid)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

        VerifyResult verifyResult = verificationRepository.findByDocument(document)
                .orElseThrow(() -> new VerificationNotFoundException("Verification result not found"));

        if (!document.isDeleted()) {
            updateDocumentStatus(document, verifyResult);
        }

        return VerifyResultDTO.fromEntity(verifyResult);
    }

    @Transactional
    public CompletableFuture<Map<String, URI>> uploadDocument(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        Document document = documentRepository.save(new Document(
                file.getOriginalFilename(),
                determinationDocumentType(file.getOriginalFilename()),
                file.getBytes()
        ));

        VerifyDocumentCommand command = new VerifyDocumentCommand(
                document.getUuid(),
                document.getType(),
                document.getContent()
        );

        URI location = URI.create("/api/documents/status/" + document.getUuid());

        return verifyDocumentHandler.handle(command)
                .thenApply(result -> Map.of("link", location));
    }

    private DocumentType determinationDocumentType(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename is required.");
        }

        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase();

        return switch (extension) {
            case PDF_EXTENSION -> DocumentType.PDF;
            case XML_EXTENSION -> DocumentType.XML;
            default -> throw new IllegalArgumentException("Unsupported document type: " + filename);
        };
    }

    private void updateDocumentStatus(Document document, VerifyResult verifyResult) {
        if (!EnumSet.of(VerifyStatus.COMPLETED, VerifyStatus.ERROR).contains(verifyResult.getStatus())) {
            return;
        }

        document.setDeleted(true);
        document.setStatus(switch (verifyResult.getStatus()) {
            case COMPLETED -> DocumentStatus.VERIFIED;
            case ERROR -> DocumentStatus.ERROR;
            default -> throw new IllegalStateException("Unexpected status: " + verifyResult.getStatus());
        });

        documentRepository.save(document);
    }
}
