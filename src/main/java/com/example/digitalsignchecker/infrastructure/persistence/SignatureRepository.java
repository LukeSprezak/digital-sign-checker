package com.example.digitalsignchecker.infrastructure.persistence;

import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.Signature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SignatureRepository extends JpaRepository<Signature, Long> {

    List<Signature> findByDocument(Document document);
    List<Signature> findByDocumentUuid(UUID documentUuid);
}
