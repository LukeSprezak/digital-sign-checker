package com.example.digitalsignchecker.ui.controller;

import com.example.digitalsignchecker.application.dto.SignatureDTO;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.service.DocumentService;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.domain.exception.DocumentNotFoundException;
import com.example.digitalsignchecker.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DocumentController.class, properties = "app.api-key=" + DocumentControllerTest.API_KEY)
@Import(SecurityConfig.class)
class DocumentControllerTest {

    static final String API_KEY = "test-key";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final MockMultipartFile PDF = new MockMultipartFile("file", "document.pdf", "application/pdf", "%PDF-1.4".getBytes());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DocumentService documentService;

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload").file(PDF))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRequestWithWrongApiKey() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload").file(PDF).header(API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsUploadAndReturnsStatusLocation() throws Exception {
        URI location = URI.create("/api/documents/status/" + UUID.randomUUID());
        when(documentService.uploadDocument(any())).thenReturn(location);

        mockMvc.perform(multipart("/api/documents/upload").file(PDF).header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", location.toString()))
                .andExpect(jsonPath("$.link").value(location.toString()));
    }

    @Test
    void rejectsUploadWithoutFilePart() throws Exception {
        mockMvc.perform(multipart("/api/documents/upload").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidUpload() throws Exception {
        when(documentService.uploadDocument(any())).thenThrow(new IllegalArgumentException("Unsupported document type: a.txt"));

        mockMvc.perform(multipart("/api/documents/upload").file(PDF).header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Unsupported document type: a.txt"));
    }

    @Test
    void rejectsTooLargeUpload() throws Exception {
        when(documentService.uploadDocument(any())).thenThrow(new MaxUploadSizeExceededException(20L * 1024 * 1024));

        mockMvc.perform(multipart("/api/documents/upload").file(PDF).header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void returnsVerificationStatus() throws Exception {
        UUID uuid = UUID.randomUUID();
        UUID resultUuid = UUID.randomUUID();
        when(documentService.getVerifyResult(uuid)).thenReturn(new VerifyResultDTO(
                resultUuid,
                VerifyStatus.COMPLETED,
                true,
                "valid",
                Instant.parse("2026-01-01T10:00:00Z"),
                List.of(new SignatureDTO("CN=Signer", "CN=CA", Instant.parse("2026-01-01T09:00:00Z")))
        ));

        mockMvc.perform(get("/api/documents/status/{uuid}", uuid).header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uuid").value(resultUuid.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.verificationTime").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.signatures[0].signerName").value("CN=Signer"));
    }

    @Test
    void rejectsMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/documents/status/{uuid}", "not-a-uuid").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportsUnknownDocument() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(documentService.getVerifyResult(uuid)).thenThrow(new DocumentNotFoundException("Document not found"));

        mockMvc.perform(get("/api/documents/status/{uuid}", uuid).header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Document not found"));
    }

    @Test
    void rejectsUnsupportedHttpMethod() throws Exception {
        mockMvc.perform(put("/api/documents/status/{uuid}", UUID.randomUUID()).header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void reportsUnknownPath() throws Exception {
        mockMvc.perform(get("/api/unknown").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isNotFound());
    }
}
