package com.example.digitalsignchecker.ui.controller;

import com.example.digitalsignchecker.application.dto.SignatureDTO;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.application.service.VerifyResultService;
import com.example.digitalsignchecker.domain.enums.VerifyStatus;
import com.example.digitalsignchecker.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = VerifyResultController.class, properties = "app.api-key=" + VerifyResultControllerTest.API_KEY)
@Import(SecurityConfig.class)
class VerifyResultControllerTest {

    static final String API_KEY = "test-key";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VerifyResultService verifyResultService;

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mockMvc.perform(get("/api/verify-results"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsResultsWithoutDocumentContent() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(verifyResultService.getAllVerifiedResults()).thenReturn(List.of(new VerifyResultDTO(
                uuid,
                VerifyStatus.INVALID,
                false,
                "tampered",
                Instant.parse("2026-01-01T10:00:00Z"),
                List.of(new SignatureDTO("CN=Signer", "CN=CA", null))
        )));

        mockMvc.perform(get("/api/verify-results").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].uuid").value(uuid.toString()))
                .andExpect(jsonPath("$[0].status").value("INVALID"))
                .andExpect(jsonPath("$[0].signatures[0].signerName").value("CN=Signer"))
                .andExpect(jsonPath("$[0].document").doesNotExist())
                .andExpect(jsonPath("$[0].content").doesNotExist());
    }
}
