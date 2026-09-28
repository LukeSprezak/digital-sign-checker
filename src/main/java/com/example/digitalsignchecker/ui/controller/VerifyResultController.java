package com.example.digitalsignchecker.ui.controller;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.service.VerifyResultService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(path = "/api/verify-results", produces = MediaType.APPLICATION_JSON_VALUE)
public class VerifyResultController {

    private final VerifyResultService verifyResultService;

    public VerifyResultController(VerifyResultService verifyResultService) {
        this.verifyResultService = verifyResultService;
    }

    @GetMapping
    public ResponseEntity<List<VerifyResultDTO>> getAllVerifiedResults() {
        return ResponseEntity.ok(verifyResultService.getAllVerifiedResults());
    }
}
