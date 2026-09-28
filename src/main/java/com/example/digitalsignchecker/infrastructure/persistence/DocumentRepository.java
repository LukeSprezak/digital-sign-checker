package com.example.digitalsignchecker.infrastructure.persistence;

import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    // Bulk update so finishing a verification does not load and rewrite the LONGBLOB content.
    @Transactional
    @Modifying
    @Query("update Document d set d.status = :status, d.deleted = true where d.id = :id")
    void finishVerification(@Param("id") Long id, @Param("status") DocumentStatus status);
}
