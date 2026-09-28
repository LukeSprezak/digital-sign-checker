package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.query.GetAllVerifyResultsQuery;
import com.example.digitalsignchecker.application.query.handler.GetAllVerifyResultsHandler;
import com.example.digitalsignchecker.infrastructure.persistence.SignatureRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VerifyResultService {

    private final GetAllVerifyResultsHandler getAllVerifyResultsHandler;
    private final SignatureRepository signatureRepository;

    public VerifyResultService(GetAllVerifyResultsHandler getAllVerifyResultsHandler, SignatureRepository signatureRepository) {
        this.getAllVerifyResultsHandler = getAllVerifyResultsHandler;
        this.signatureRepository = signatureRepository;
    }

    public List<VerifyResultDTO> getAllVerifiedResults() {
        return getAllVerifyResultsHandler.handle(new GetAllVerifyResultsQuery()).stream()
                .map(result -> VerifyResultDTO.fromEntity(result, signatureRepository.findByDocument(result.getDocument())))
                .toList();
    }
}
