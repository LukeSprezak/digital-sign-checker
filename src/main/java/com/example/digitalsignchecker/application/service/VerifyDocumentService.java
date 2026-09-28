package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.DocumentNotFoundException;
import com.example.digitalsignchecker.domain.exception.VerificationNotFoundException;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.Signature;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import com.example.digitalsignchecker.application.service.strategy.PdfVerifyService;
import com.example.digitalsignchecker.application.service.strategy.XmlVerifyService;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.SignatureRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class VerifyDocumentService {

    private static final Logger logger = LoggerFactory.getLogger(VerifyDocumentService.class);
    private final VerifyRepository verifyRepository;
    private final DocumentRepository documentRepository;
    private final SignatureRepository signatureRepository;
    private final Map<DocumentType, DocumentVerifyStrategy> verifyStrategy;

    public VerifyDocumentService(
            VerifyRepository verifyRepository,
            DocumentRepository documentRepository,
            SignatureRepository signatureRepository,
            List<DocumentVerifyStrategy> strategies
    ) {
        this.verifyRepository = verifyRepository;
        this.documentRepository = documentRepository;
        this.signatureRepository = signatureRepository;
        this.verifyStrategy = beginStrategy(strategies);
    }

    public void verifyDocument(VerifyDocumentCommand command) {
        Document document = documentRepository.findByUuid(command.uuid())
                .orElseThrow(() -> new DocumentNotFoundException("Document not found with id: " + command.uuid()));
        VerifyResult verifyResult = verifyRepository.findFirstByDocumentOrderByIdDesc(document)
                .orElseThrow(() -> new VerificationNotFoundException("Verification result not found for document: " + command.uuid()));

        try {
            processVerify(command, document, verifyResult);
        } catch (Exception exception) {
            logger.error("Verify failed for document {}", command.uuid(), exception);
            finishVerify(document, verifyResult, VerifyStatus.ERROR, "ERROR: " + exception.getMessage());
        }
    }

    private void processVerify(VerifyDocumentCommand command, Document document, VerifyResult verifyResult) {
        DocumentVerifyStrategy strategy = verifyStrategy.get(command.type());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported document type: " + command.type());
        }

        updateVerifyStatus(verifyResult, VerifyStatus.IN_PROGRESS, "Verify in progress");

        VerifyResultDTO resultDTO = strategy.verifyDocument(command.data());

        signatureRepository.saveAll(resultDTO.signatures().stream()
                .map(dto -> new Signature(document, dto.signerName(), dto.certificateIssuer(), dto.signingTime()))
                .toList());

        VerifyStatus finalStatus = resultDTO.verified()
                ? VerifyStatus.COMPLETED
                : VerifyStatus.ERROR;

        finishVerify(document, verifyResult, finalStatus, resultDTO.message());
    }

    private void finishVerify(Document document, VerifyResult verifyResult, VerifyStatus status, String message) {
        updateVerifyStatus(verifyResult, status, message);

        document.setDeleted(true);
        document.setStatus(status == VerifyStatus.COMPLETED ? DocumentStatus.VERIFIED : DocumentStatus.ERROR);
        documentRepository.save(document);
    }

    private void updateVerifyStatus(VerifyResult verifyResult, VerifyStatus status, String message) {
        if (!verifyResult.getStatus().equals(status)) {
            verifyResult.updateStatus(status, status == VerifyStatus.COMPLETED, message);
            verifyRepository.save(verifyResult);
        }
    }

    private Map<DocumentType, DocumentVerifyStrategy> beginStrategy(List<DocumentVerifyStrategy> strategies) {
        return strategies.stream()
                .collect(Collectors.toMap(this::strategyDocumentType, strategy -> strategy));
    }

    private DocumentType strategyDocumentType(DocumentVerifyStrategy strategy) {
        return switch (strategy) {
            case PdfVerifyService ignored -> DocumentType.PDF;
            case XmlVerifyService ignored -> DocumentType.XML;
            default -> throw new IllegalArgumentException("Unknown verify strategy: " + strategy.getClass());
        };
    }
}
