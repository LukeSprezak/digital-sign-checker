package com.example.digitalsignchecker.application.service;

import com.example.digitalsignchecker.application.command.VerifyDocumentCommand;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.DocumentNotFoundException;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.domain.model.Signature;
import com.example.digitalsignchecker.domain.model.VerifyResult;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.example.digitalsignchecker.infrastructure.persistence.SignatureRepository;
import com.example.digitalsignchecker.infrastructure.persistence.VerifyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.AdditionalAnswers.returnsFirstArg;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final byte[] PDF_CONTENT = "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] XML_CONTENT = "<?xml version=\"1.0\"?><root/>".getBytes(StandardCharsets.UTF_8);

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private VerifyRepository verifyRepository;
    @Mock
    private SignatureRepository signatureRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private DocumentService documentService;

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> documentService.uploadDocument(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File is required");
    }

    @Test
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> documentService.uploadDocument(file("document.pdf", new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File is required");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingFilename(String filename) {
        assertThatThrownBy(() -> documentService.uploadDocument(file(filename, PDF_CONTENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Filename is required.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"document", "document.txt", "document.pdf.exe"})
    void rejectsUnsupportedExtension(String filename) {
        assertThatThrownBy(() -> documentService.uploadDocument(file(filename, PDF_CONTENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Unsupported document type");
        verifyNoInteractions(documentRepository, eventPublisher);
    }

    @Test
    void rejectsPdfExtensionWithXmlContent() {
        assertThatThrownBy(() -> documentService.uploadDocument(file("document.pdf", XML_CONTENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("File content does not match its extension");
    }

    @Test
    void rejectsXmlExtensionWithPdfContent() {
        assertThatThrownBy(() -> documentService.uploadDocument(file("document.xml", PDF_CONTENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("File content does not match its extension");
    }

    @Test
    void acceptsUppercaseExtension() throws IOException {
        stubSaves();

        documentService.uploadDocument(file("DOCUMENT.PDF", PDF_CONTENT));

        assertThat(publishedCommand().type()).isEqualTo(DocumentType.PDF);
    }

    @Test
    void acceptsXmlWithByteOrderMarkAndLeadingWhitespace() throws IOException {
        stubSaves();
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = new byte[bom.length + 2 + XML_CONTENT.length];
        System.arraycopy(bom, 0, content, 0, bom.length);
        content[3] = ' ';
        content[4] = '\n';
        System.arraycopy(XML_CONTENT, 0, content, 5, XML_CONTENT.length);

        documentService.uploadDocument(file("document.xml", content));

        assertThat(publishedCommand().type()).isEqualTo(DocumentType.XML);
    }

    @Test
    void savesDocumentWithPendingVerificationAndPublishesCommand() throws IOException {
        stubSaves();

        URI location = documentService.uploadDocument(file("document.pdf", PDF_CONTENT));

        ArgumentCaptor<Document> document = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(document.capture());
        assertThat(document.getValue().getFilename()).isEqualTo("document.pdf");
        assertThat(document.getValue().getContent()).isEqualTo(PDF_CONTENT);

        ArgumentCaptor<VerifyResult> verifyResult = ArgumentCaptor.forClass(VerifyResult.class);
        verify(verifyRepository).save(verifyResult.capture());
        assertThat(verifyResult.getValue().getStatus()).isEqualTo(VerifyStatus.PENDING);
        assertThat(verifyResult.getValue().getDocument()).isSameAs(document.getValue());

        VerifyDocumentCommand command = publishedCommand();
        assertThat(command.type()).isEqualTo(DocumentType.PDF);
        assertThat(command.data()).isEqualTo(PDF_CONTENT);

        assertThat(location).isEqualTo(URI.create("/api/documents/status/" + document.getValue().getUuid()));
    }

    @Test
    void reportsMissingDocumentStatus() {
        UUID uuid = UUID.randomUUID();
        when(verifyRepository.findFirstByDocumentUuidOrderByIdDesc(uuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.getVerifyResult(uuid))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void returnsVerificationWithSignatures() {
        UUID uuid = UUID.randomUUID();
        VerifyResult verifyResult = new VerifyResult(null, VerifyStatus.COMPLETED, true, "ok");
        Instant signingTime = Instant.parse("2026-01-01T10:00:00Z");
        when(verifyRepository.findFirstByDocumentUuidOrderByIdDesc(uuid)).thenReturn(Optional.of(verifyResult));
        when(signatureRepository.findByDocumentUuid(uuid))
                .thenReturn(List.of(new Signature(null, "CN=Signer", "CN=CA", signingTime)));

        VerifyResultDTO result = documentService.getVerifyResult(uuid);

        assertThat(result.uuid()).isEqualTo(verifyResult.getUuid());
        assertThat(result.status()).isEqualTo(VerifyStatus.COMPLETED);
        assertThat(result.verified()).isTrue();
        assertThat(result.signatures()).singleElement().satisfies(signature -> {
            assertThat(signature.signerName()).isEqualTo("CN=Signer");
            assertThat(signature.certificateIssuer()).isEqualTo("CN=CA");
            assertThat(signature.signingTime()).isEqualTo(signingTime);
        });
    }

    private void stubSaves() {
        when(documentRepository.save(any(Document.class))).then(returnsFirstArg());
        when(verifyRepository.save(any(VerifyResult.class))).then(returnsFirstArg());
    }

    private VerifyDocumentCommand publishedCommand() {
        ArgumentCaptor<VerifyDocumentCommand> command = ArgumentCaptor.forClass(VerifyDocumentCommand.class);
        verify(eventPublisher).publishEvent(command.capture());
        return command.getValue();
    }

    private static MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, null, content);
    }
}
