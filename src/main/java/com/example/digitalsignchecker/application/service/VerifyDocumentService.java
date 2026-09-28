package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.VerificationNotFoundException;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.Signature;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.SignatureRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
        this.verifyStrategy = strategies.stream()
                .collect(Collectors.toMap(DocumentVerifyStrategy::supportedType, Function.identity()));
    }

    public void verifyDocument(VerifyDocumentCommand command) {
        VerifyResult verifyResult = verifyRepository.findFirstByDocumentIdOrderByIdDesc(command.documentId())
                .orElseThrow(() -> new VerificationNotFoundException("Verification result not found for document: " + command.documentId()));

        try {
            processVerify(command, verifyResult);
        } catch (Exception exception) {
            logger.error("Verify failed for document {}", command.documentId(), exception);
            finishVerify(command.documentId(), verifyResult, VerifyStatus.ERROR, "ERROR: " + exception.getMessage());
        }
    }

    private void processVerify(VerifyDocumentCommand command, VerifyResult verifyResult) {
        DocumentVerifyStrategy strategy = verifyStrategy.get(command.type());

        updateVerifyStatus(verifyResult, VerifyStatus.IN_PROGRESS, "Verify in progress");

        VerificationOutcome outcome = strategy.verifyDocument(command.data());

        Document document = documentRepository.getReferenceById(command.documentId());
        signatureRepository.saveAll(outcome.signatures().stream()
                .map(dto -> new Signature(document, dto.signerName(), dto.certificateIssuer(), dto.signingTime()))
                .toList());

        VerifyStatus finalStatus = outcome.verified()
                ? VerifyStatus.COMPLETED
                : VerifyStatus.INVALID;

        finishVerify(command.documentId(), verifyResult, finalStatus, outcome.message());
    }

    private void finishVerify(Long documentId, VerifyResult verifyResult, VerifyStatus status, String message) {
        updateVerifyStatus(verifyResult, status, message);

        documentRepository.finishVerification(documentId, switch (status) {
            case COMPLETED -> DocumentStatus.VERIFIED;
            case INVALID -> DocumentStatus.INVALID;
            case ERROR -> DocumentStatus.ERROR;
            case PENDING, IN_PROGRESS -> throw new IllegalStateException("Verification is not finished: " + status);
        });
    }

    private void updateVerifyStatus(VerifyResult verifyResult, VerifyStatus status, String message) {
        if (!verifyResult.getStatus().equals(status)) {
            verifyResult.updateStatus(status, status == VerifyStatus.COMPLETED, message);
            verifyRepository.save(verifyResult);
        }
    }
}
