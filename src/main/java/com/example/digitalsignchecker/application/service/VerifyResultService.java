package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.query.GetAllVerifyResultsQuery;
import com.example.digitalsignchecker.application.query.handler.GetAllVerifyResultsHandler;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VerifyResultService {

    private final GetAllVerifyResultsHandler getAllVerifyResultsHandler;

    public VerifyResultService(GetAllVerifyResultsHandler getAllVerifyResultsHandler) {
        this.getAllVerifyResultsHandler = getAllVerifyResultsHandler;
    }

    public List<VerifyResult> getAllVerifiedResults() {
        return getAllVerifyResultsHandler.handle(new GetAllVerifyResultsQuery());
    }
}
