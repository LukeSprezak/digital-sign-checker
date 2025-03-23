package com.example.digitalsignchecker.infrastructure.persistence;

import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerifyRepository extends JpaRepository<VerifyResult, Long> {

    Optional<VerifyResult> findByUuid(UUID uuid);
    Optional<VerifyResult> findByDocument(Document document);
    List<VerifyResult> findAllByStatusIn(Collection<VerifyStatus> status);
    default List<VerifyResult> findAllVerified() {
        return findAllByStatusIn(List.of(VerifyStatus.COMPLETED, VerifyStatus.ERROR));
    }
}
