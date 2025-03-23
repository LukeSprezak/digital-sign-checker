package com.example.digitalsignchecker.application.command.handler;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.service.VerifyDocumentService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class VerifyDocumentHandler {

    private final VerifyDocumentService verifyDocumentService;

    public VerifyDocumentHandler(VerifyDocumentService verifyDocumentService) {
        this.verifyDocumentService = verifyDocumentService;
    }

    @Async
    public CompletableFuture<VerifyResultDTO> handle(VerifyDocumentCommand command) {
        return verifyDocumentService.verifyDocument(command);
    }
}
