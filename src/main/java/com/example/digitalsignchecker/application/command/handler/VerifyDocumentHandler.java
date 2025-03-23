package com.example.digitalsignchecker.application.command.handler;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import com.example.digitalsignchecker.domain.service.PdfVerifyService;
import com.example.digitalsignchecker.domain.service.XmlVerifyService;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class VerifyDocumentHandler {

    private static final Logger logger = LoggerFactory.getLogger(VerifyDocumentHandler.class);
    private final VerifyRepository verifyRepository;
    private final DocumentRepository documentRepository;
    private final Map<DocumentType, DocumentVerifyStrategy> verifyStrategy;

    public VerifyDocumentHandler(
            VerifyRepository verifyRepository,
            DocumentRepository documentRepository,
            List<DocumentVerifyStrategy> strategies
    ) {
        this.verifyRepository = verifyRepository;
        this.documentRepository = documentRepository;
        this.verifyStrategy = beginStrategy(strategies);
    }

    @Async
    public CompletableFuture<VerifyResultDTO> handle(VerifyDocumentCommand command) {
        UUID documentId = command.uuid();
        Document document = documentRepository.findByUuid(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with id: " + documentId));

        VerifyResult verifyResult = saveInitialVerify(document);

        return CompletableFuture
                .supplyAsync(() -> processVerify(command, verifyResult))
                .exceptionally(exception -> handleVerifyError(verifyResult, exception));
    }

    private VerifyResult saveInitialVerify(Document document) {
        VerifyResult verifyResult = new VerifyResult(document, VerifyStatus.PENDING, false, "Begin processing");
        return verifyRepository.save(verifyResult);
    }

    private VerifyResultDTO processVerify(VerifyDocumentCommand command, VerifyResult verifyResult) {
        DocumentVerifyStrategy strategy = verifyStrategy.get(command.type());
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported document type: " + command.type());
        }

        updateVerifyStatus(verifyResult, VerifyStatus.IN_PROGRESS, "Verify in progress");

        VerifyResultDTO resultDTO = strategy.verifyDocument(command.data());
        VerifyStatus finalStatus = resultDTO.verified()
                ? VerifyStatus.COMPLETED
                : VerifyStatus.ERROR;

        updateVerifyStatus(verifyResult, finalStatus, resultDTO.message());

        return resultDTO;
    }

    private void updateVerifyStatus(VerifyResult verifyResult, VerifyStatus status, String message) {
        if (!verifyResult.getStatus().equals(status)) {
            verifyResult.updateStatus(status, status == VerifyStatus.COMPLETED, message);
            verifyRepository.save(verifyResult);
        }
    }

    private VerifyResultDTO handleVerifyError(VerifyResult verifyResult, Throwable exception) {
        String errorMessage = "ERROR: " + (exception.getMessage() != null ? exception.getMessage() : "Unknown error");
        logger.error("Verify failed: {}", errorMessage);

        updateVerifyStatus(verifyResult, VerifyStatus.ERROR, errorMessage);
        return VerifyResultDTO.fromEntity(verifyResult);
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
