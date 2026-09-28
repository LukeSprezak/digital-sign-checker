package com.example.digitalsignchecker.application.command.handler;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.service.VerifyDocumentService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class VerifyDocumentHandler {

    private final VerifyDocumentService verifyDocumentService;

    public VerifyDocumentHandler(VerifyDocumentService verifyDocumentService) {
        this.verifyDocumentService = verifyDocumentService;
    }

    // Runs after the upload transaction commits, so the document is visible to the worker thread.
    @Async
    @TransactionalEventListener
    public void handle(VerifyDocumentCommand command) {
        verifyDocumentService.verifyDocument(command);
    }
}
