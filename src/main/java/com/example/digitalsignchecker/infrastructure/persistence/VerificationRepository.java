package com.example.digitalsignchecker.infrastructure.persistence;

import com.example.digitalsignchecker.domain.model.VerifyResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationRepository extends JpaRepository<VerifyResult, Long> {

    Optional<VerifyResult> findByUuid(UUID uuid);
}
