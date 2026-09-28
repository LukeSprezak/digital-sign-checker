package com.example.digitalsignchecker.ui.controller;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.service.DocumentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/status/{uuid}")
    public ResponseEntity<VerifyResultDTO> getVerifyResultForSignature(@PathVariable UUID uuid) {
        return ResponseEntity.ok(documentService.getVerifyResult(uuid));
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, URI>> uploadDocumentToVerified(
            @RequestParam("file") MultipartFile file) throws IOException {
        URI location = documentService.uploadDocument(file);
        return ResponseEntity.accepted().location(location).body(Map.of("link", location));
    }
}
