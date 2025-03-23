package com.example.digitalsignchecker.application.command.handler;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerificationStatus;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.domain.service.DocumentVerificationStrategy;
import com.example.digitalsignchecker.domain.service.PdfVerificationService;
import com.example.digitalsignchecker.domain.service.XmlVerificationService;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class VerifyDocumentHandler {

    private static final Logger logger = LoggerFactory.getLogger(VerifyDocumentHandler.class);
    private final VerificationRepository verificationRepository;
    private final DocumentRepository documentRepository;
    private final Map<DocumentType, DocumentVerificationStrategy> verificationStrategies;

    @Autowired
    public VerifyDocumentHandler(VerificationRepository verificationRepository, DocumentRepository documentRepository, List<DocumentVerificationStrategy> strategies) {
        this.verificationRepository = verificationRepository;
        this.documentRepository = documentRepository;
        this.verificationStrategies = strategies.stream()
                .collect(Collectors.toMap(strategy -> {
                    if (strategy instanceof PdfVerificationService) {
                        return DocumentType.PDF;
                    } else if (strategy instanceof XmlVerificationService) {
                        return DocumentType.XML;
                    }
                    throw new IllegalArgumentException("Unknown verification strategy: " + strategy.getClass());
                }, strategy -> strategy));
    }

    @Async
    public CompletableFuture<VerifyResultDTO> handle(VerifyDocumentCommand command) {
        UUID documentId = command.uuid();
        Document document = documentRepository.findByUuid(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with id: " + documentId));

        VerifyResult verifyResult = new VerifyResult(document, VerificationStatus.PENDING, false, "Processing started");
        verificationRepository.save(verifyResult);

        return CompletableFuture.supplyAsync(() -> {
            DocumentVerificationStrategy strategy = verificationStrategies.get(command.type());
            if (strategy == null) {
                throw new IllegalArgumentException("Unsupported document type: " + command.type());
            }

            verifyResult.updateStatus(VerificationStatus.IN_PROGRESS, false, "Verification in progress");
            verificationRepository.save(verifyResult);

            VerifyResultDTO resultDTO = strategy.verifyDocument(command.data());

            VerificationStatus finalStatus = resultDTO.verified() ? VerificationStatus.COMPLETED : VerificationStatus.ERROR;
            verifyResult.updateStatus(finalStatus, resultDTO.verified(), resultDTO.message());
            verificationRepository.save(verifyResult);

            if (command.callbackUrl() != null && !command.callbackUrl().isEmpty()) {
                sendCallback(command.callbackUrl(), resultDTO);
            }

            return resultDTO;
        }).exceptionally(exception -> {
            verifyResult.updateStatus(VerificationStatus.ERROR, false, "ERROR: " + exception.getMessage());
            verificationRepository.save(verifyResult);

            return VerifyResultDTO.fromEntity(verifyResult);
        });
    }

    private void sendCallback(String callbackUrl, VerifyResultDTO resultDTO) {
        RestTemplate restTemplate = new RestTemplate();
        try {
            logger.info("Sending callback to {}", callbackUrl);
            restTemplate.postForEntity(callbackUrl, resultDTO, Void.class);
        } catch (Exception exception) {
            logger.error("Failed to send callback to {}: {}", callbackUrl, exception.getMessage());
        }
    }
}
