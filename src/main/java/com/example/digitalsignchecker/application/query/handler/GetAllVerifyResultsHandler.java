package com.example.digitalsignchecker.application.query.handler;

import com.example.digitalsignchecker.application.query.GetAllVerifyResultsQuery;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GetAllVerifyResultsHandler {

    private final VerifyRepository verificationRepository;

    public GetAllVerifyResultsHandler(VerifyRepository verificationRepository) {
        this.verificationRepository = verificationRepository;
    }

    public List<VerifyResult> handle(GetAllVerifyResultsQuery query) {
        return verificationRepository.findAllVerified();
    }
}
