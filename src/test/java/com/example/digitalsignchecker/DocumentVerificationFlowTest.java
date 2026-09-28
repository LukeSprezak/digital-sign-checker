package com.example.digitalsignchecker;

import com.example.digitalsignchecker.domain.enums.DocumentStatus;
import com.example.digitalsignchecker.domain.model.Document;
import com.example.digitalsignchecker.infrastructure.persistence.DocumentRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.example.digitalsignchecker.support.TestPdfs.SIGNER_NAME;
import static com.example.digitalsignchecker.support.TestPdfs.sign;
import static com.example.digitalsignchecker.support.TestPdfs.unsignedPdf;
import static com.example.digitalsignchecker.support.TestPdfs.validSigner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.api-key=test-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DocumentVerificationFlowTest {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY = "test-key";
    private static final Set<String> FINAL_STATUSES = Set.of("COMPLETED", "INVALID", "ERROR");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void verifiesSignedPdfEndToEnd() throws Exception {
        String location = upload("signed.pdf", sign(unsignedPdf(), validSigner()));

        String result = awaitFinalStatus(location);

        assertThat(JsonPath.<String>read(result, "$.status")).isEqualTo("COMPLETED");
        assertThat(JsonPath.<Boolean>read(result, "$.verified")).isTrue();
        assertThat(JsonPath.<String>read(result, "$.signatures[0].signerName")).isEqualTo(SIGNER_NAME);
        assertThat(documentFor(location).getStatus()).isEqualTo(DocumentStatus.VERIFIED);
        assertThat(documentFor(location).isDeleted()).isTrue();
    }

    @Test
    void marksUnsignedPdfAsInvalid() throws Exception {
        String location = upload("unsigned.pdf", unsignedPdf());

        String result = awaitFinalStatus(location);

        assertThat(JsonPath.<String>read(result, "$.status")).isEqualTo("INVALID");
        assertThat(JsonPath.<Boolean>read(result, "$.verified")).isFalse();
        assertThat(JsonPath.<String>read(result, "$.message")).contains("does NOT contain a PAdES signature");
        assertThat(documentFor(location).getStatus()).isEqualTo(DocumentStatus.INVALID);
    }

    @Test
    void listsFinishedVerifications() throws Exception {
        String location = upload("listed.pdf", unsignedPdf());
        String result = awaitFinalStatus(location);
        String resultUuid = JsonPath.read(result, "$.uuid");

        String list = mockMvc.perform(get("/api/verify-results").header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<java.util.List<String>>read(list, "$[*].uuid")).contains(resultUuid);
    }

    private String upload(String filename, byte[] content) throws Exception {
        return mockMvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", filename, "application/pdf", content))
                        .header(API_KEY_HEADER, API_KEY))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getHeader("Location");
    }

    // Verification runs asynchronously after the upload transaction commits.
    private String awaitFinalStatus(String location) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (true) {
            String result = mockMvc.perform(get(location).header(API_KEY_HEADER, API_KEY))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            if (FINAL_STATUSES.contains(JsonPath.<String>read(result, "$.status"))) {
                return result;
            }
            assertThat(Instant.now()).as("verification did not finish in time").isBefore(deadline);
            Thread.sleep(100);
        }
    }

    private Document documentFor(String location) {
        UUID uuid = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        return documentRepository.findAll().stream()
                .filter(document -> document.getUuid().equals(uuid))
                .findFirst()
                .orElseThrow();
    }
}
