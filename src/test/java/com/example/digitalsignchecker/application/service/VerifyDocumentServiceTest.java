package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.SignatureDTO;
import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.VerificationNotFoundException;
import com.example.digitalsignchecker.domain.model.Signature;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.SignatureRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifyDocumentServiceTest {

    private static final long DOCUMENT_ID = 42L;
    private static final byte[] DATA = {1, 2, 3};

    @Mock
    private VerifyRepository verifyRepository;
    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private SignatureRepository signatureRepository;
    @Mock
    private DocumentVerifyStrategy pdfStrategy;
    @Mock
    private DocumentVerifyStrategy xmlStrategy;

    private VerifyDocumentService service;
    private VerifyResult verifyResult;
    // VerifyResult is mutated in place, so each save is recorded to see the intermediate statuses.
    private final List<VerifyStatus> savedStatuses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(pdfStrategy.supportedType()).thenReturn(DocumentType.PDF);
        when(xmlStrategy.supportedType()).thenReturn(DocumentType.XML);
        service = new VerifyDocumentService(verifyRepository, documentRepository, signatureRepository, List.of(pdfStrategy, xmlStrategy));
        verifyResult = new VerifyResult(null, VerifyStatus.PENDING, false, "Begin processing");
    }

    @Test
    void completesVerificationOfValidDocument() {
        stubVerifyResult();
        SignatureDTO signature = new SignatureDTO("CN=Signer", "CN=CA", Instant.parse("2026-01-01T10:00:00Z"));
        when(pdfStrategy.verifyDocument(DATA)).thenReturn(new VerificationOutcome(true, "valid", List.of(signature)));

        service.verifyDocument(new VerifyDocumentCommand(DOCUMENT_ID, DocumentType.PDF, DATA));

        assertThat(savedStatuses).containsExactly(VerifyStatus.IN_PROGRESS, VerifyStatus.COMPLETED);
        assertThat(verifyResult.isVerified()).isTrue();
        assertThat(verifyResult.getMessage()).isEqualTo("valid");
        verify(documentRepository).finishVerification(DOCUMENT_ID, DocumentStatus.VERIFIED);
        assertThat(savedSignatures()).singleElement().satisfies(saved -> {
            assertThat(saved.getSignerName()).isEqualTo("CN=Signer");
            assertThat(saved.getCertificateIssuer()).isEqualTo("CN=CA");
            assertThat(saved.getSigningTime()).isEqualTo(signature.signingTime());
        });
    }

    @Test
    void marksDocumentWithInvalidSignatureAsInvalid() {
        stubVerifyResult();
        when(pdfStrategy.verifyDocument(DATA)).thenReturn(new VerificationOutcome(false, "tampered", List.of()));

        service.verifyDocument(new VerifyDocumentCommand(DOCUMENT_ID, DocumentType.PDF, DATA));

        assertThat(savedStatuses).containsExactly(VerifyStatus.IN_PROGRESS, VerifyStatus.INVALID);
        assertThat(verifyResult.isVerified()).isFalse();
        assertThat(verifyResult.getMessage()).isEqualTo("tampered");
        verify(documentRepository).finishVerification(DOCUMENT_ID, DocumentStatus.INVALID);
    }

    @Test
    void marksVerificationAsErrorWhenStrategyFails() {
        stubVerifyResult();
        when(pdfStrategy.verifyDocument(DATA)).thenThrow(new IllegalStateException("boom"));

        service.verifyDocument(new VerifyDocumentCommand(DOCUMENT_ID, DocumentType.PDF, DATA));

        assertThat(savedStatuses).containsExactly(VerifyStatus.IN_PROGRESS, VerifyStatus.ERROR);
        assertThat(verifyResult.getMessage()).isEqualTo("ERROR: boom");
        verify(documentRepository).finishVerification(DOCUMENT_ID, DocumentStatus.ERROR);
        verify(signatureRepository, never()).saveAll(any());
    }

    @Test
    void routesCommandToStrategyForDocumentType() {
        stubVerifyResult();
        when(xmlStrategy.verifyDocument(DATA)).thenReturn(new VerificationOutcome(false, "unsupported", List.of()));

        service.verifyDocument(new VerifyDocumentCommand(DOCUMENT_ID, DocumentType.XML, DATA));

        verify(xmlStrategy).verifyDocument(DATA);
        verify(pdfStrategy, never()).verifyDocument(any());
    }

    @Test
    void failsWhenVerificationResultIsMissing() {
        when(verifyRepository.findFirstByDocumentIdOrderByIdDesc(DOCUMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyDocument(new VerifyDocumentCommand(DOCUMENT_ID, DocumentType.PDF, DATA)))
                .isInstanceOf(VerificationNotFoundException.class);
        verify(documentRepository, never()).finishVerification(any(), any());
    }

    private void stubVerifyResult() {
        when(verifyRepository.findFirstByDocumentIdOrderByIdDesc(DOCUMENT_ID)).thenReturn(Optional.of(verifyResult));
        when(verifyRepository.save(verifyResult)).then(invocation -> {
            savedStatuses.add(verifyResult.getStatus());
            return verifyResult;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Signature> savedSignatures() {
        ArgumentCaptor<List<Signature>> signatures = ArgumentCaptor.forClass(List.class);
        verify(signatureRepository).saveAll(signatures.capture());
        return signatures.getValue();
    }
}
