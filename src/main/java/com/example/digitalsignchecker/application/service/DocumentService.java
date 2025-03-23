package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.command.handler.VerifyDocumentHandler;
import com.example.digitalsignchecker.application.dto.SignatureDTO;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.service.strategy.PdfVerifyService;
import com.example.digitalsignchecker.application.service.strategy.XmlVerifyService;
import com.example.digitalsignchecker.domain.enums.DocumentStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Service
public class DocumentService {

    private static final String PDF_EXTENSION = ".pdf";
    private static final String XML_EXTENSION = ".xml";

    private final DocumentRepository documentRepository;
    private final VerifyRepository verificationRepository;
    private final VerifyDocumentHandler verifyDocumentHandler;
    private final PdfVerifyService pdfVerifyService;
    private final XmlVerifyService xmlVerifyService;
    private final SignatureRepository signatureRepository;

    public DocumentService(
            DocumentRepository documentRepository,
            VerifyRepository verificationRepository,
            VerifyDocumentHandler verifyDocumentHandler,
            PdfVerifyService pdfVerifyService,
            XmlVerifyService xmlVerifyService,
            SignatureRepository signatureRepository
    ) {
        this.documentRepository = documentRepository;
        this.verificationRepository = verificationRepository;
        this.verifyDocumentHandler = verifyDocumentHandler;
        this.pdfVerifyService = pdfVerifyService;
        this.xmlVerifyService = xmlVerifyService;
        this.signatureRepository = signatureRepository;
    }

    @Transactional()
    public VerifyResultDTO getVerifyResult(UUID uuid) {

        Document document = documentRepository.findByUuid(uuid)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

        VerifyResult verifyResult = verificationRepository.findByDocument(document)
                .orElseThrow(() -> new VerificationNotFoundException("Verification result not found"));

        List<Signature> signatures = signatureRepository.findByDocument(document);

        if (!document.isDeleted()) {
            updateDocumentStatus(document, verifyResult);
        }

        return VerifyResultDTO.fromEntity(verifyResult, signatures);
    }

    @Transactional
    public CompletableFuture<Map<String, URI>> uploadDocument(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        Document document = saveDocumentWithSignatures(file);

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

    @Transactional
    public Document saveDocumentWithSignatures(MultipartFile file) throws IOException {

        Document document = new Document(
                file.getOriginalFilename(),
                determinationDocumentType(file.getOriginalFilename()),
                file.getBytes()
        );
        document = documentRepository.save(document);

        List<SignatureDTO> signatureDTOs = extractSignatures(document);

        if (signatureDTOs != null && !signatureDTOs.isEmpty()) {
            Document finalDocument = document;
            List<Signature> signatures = signatureDTOs.stream()
                    .map(dto -> new Signature(
                            finalDocument,
                            dto.signerName(),
                            dto.certificateIssuer(),
                            dto.signingTime()))
                    .toList();

            signatureRepository.saveAll(signatures);
        }

        return document;
    }

    private List<SignatureDTO> extractSignatures(Document document) {
        return switch (document.getType()) {
            case PDF -> pdfVerifyService.verifyDocument(document.getContent()).signatures();
            case XML -> xmlVerifyService.verifyDocument(document.getContent()).signatures();
            default -> List.of();
        };
    }
}
