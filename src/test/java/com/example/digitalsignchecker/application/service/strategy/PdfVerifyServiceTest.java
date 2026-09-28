package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import com.example.digitalsignchecker.support.TestPdfs.Signer;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static com.example.digitalsignchecker.support.TestPdfs.*;
import static org.assertj.core.api.Assertions.assertThat;

class PdfVerifyServiceTest {

    private final PdfVerifyService service = new PdfVerifyService();

    @Test
    void rejectsEmptyData() {
        VerificationOutcome outcome = service.verifyDocument(new byte[0]);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("empty");
    }

    @Test
    void rejectsDataThatIsNotPdf() {
        VerificationOutcome outcome = service.verifyDocument("not a pdf".getBytes(StandardCharsets.US_ASCII));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).startsWith("Validation error");
    }

    @Test
    void rejectsPdfWithoutSignature() throws IOException {
        VerificationOutcome outcome = service.verifyDocument(unsignedPdf());

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("does NOT contain a PAdES signature");
    }

    @Test
    void acceptsValidSignatureAndReportsSignerInsteadOfCa() throws IOException {
        VerificationOutcome outcome = service.verifyDocument(sign(unsignedPdf(), validSigner()));

        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.signatures()).singleElement().satisfies(signature -> {
            assertThat(signature.signerName()).isEqualTo(SIGNER_NAME);
            assertThat(signature.certificateIssuer()).isEqualTo(CA_NAME);
            assertThat(signature.signingTime()).isNotNull();
        });
    }

    @Test
    void rejectsDocumentModifiedAfterSigning() throws IOException {
        byte[] signed = sign(unsignedPdf(), validSigner());
        // Byte 10 lies in the binary comment after the header: inside the signed range, irrelevant to parsing.
        signed[10] ^= 0x01;

        VerificationOutcome outcome = service.verifyDocument(signed);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("does not match the document content");
    }

    @Test
    void rejectsDataAppendedAfterSignature() throws IOException {
        byte[] signed = sign(unsignedPdf(), validSigner());
        byte[] update = "\n% unsigned update\n".getBytes(StandardCharsets.US_ASCII);
        byte[] appended = Arrays.copyOf(signed, signed.length + update.length);
        System.arraycopy(update, 0, appended, signed.length, update.length);

        VerificationOutcome outcome = service.verifyDocument(appended);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("do NOT cover the entire document");
    }

    @Test
    void rejectsExpiredCertificate() throws IOException {
        Signer signer = signer(Instant.now().minus(Duration.ofDays(30)), Instant.now().minus(Duration.ofDays(1)));

        VerificationOutcome outcome = service.verifyDocument(sign(unsignedPdf(), signer));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("has expired");
    }

    @Test
    void rejectsCertificateNotYetValid() throws IOException {
        Signer signer = signer(Instant.now().plus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(30)));

        VerificationOutcome outcome = service.verifyDocument(sign(unsignedPdf(), signer));

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("is not yet valid");
    }

    @Test
    void acceptsTwoValidSignatures() throws IOException {
        byte[] signedTwice = sign(sign(unsignedPdf(), validSigner()), validSigner());

        VerificationOutcome outcome = service.verifyDocument(signedTwice);

        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.signatures()).hasSize(2);
    }

    @Test
    void rejectsDocumentWhenSecondSignatureHasExpiredCertificate() throws IOException {
        Signer expired = signer(Instant.now().minus(Duration.ofDays(30)), Instant.now().minus(Duration.ofDays(1)));
        byte[] signedTwice = sign(sign(unsignedPdf(), validSigner()), expired);

        VerificationOutcome outcome = service.verifyDocument(signedTwice);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.signatures()).hasSize(2);
        assertThat(outcome.message()).contains("has expired");
    }

    @Test
    void acceptsCertifiedDocumentWithoutLaterChanges() throws IOException {
        VerificationOutcome outcome = service.verifyDocument(certify(unsignedPdf(), validSigner(), 1));

        assertThat(outcome.verified()).isTrue();
    }

    @Test
    void rejectsChangesAfterCertificationThatForbidsThem() throws IOException {
        byte[] signedAfterCertification = sign(certify(unsignedPdf(), validSigner(), 1), validSigner());

        VerificationOutcome outcome = service.verifyDocument(signedAfterCertification);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("certification signature that forbids any changes");
    }

    @Test
    void acceptsSignatureAddedAfterCertificationThatAllowsIt() throws IOException {
        byte[] signedAfterCertification = sign(certify(unsignedPdf(), validSigner(), 2), validSigner());

        VerificationOutcome outcome = service.verifyDocument(signedAfterCertification);

        assertThat(outcome.verified()).isTrue();
    }

    @Test
    void acceptsSignatureWithDocumentTimestamp() throws IOException {
        byte[] timestamped = timestamp(sign(unsignedPdf(), validSigner()), timestampAuthority());

        VerificationOutcome outcome = service.verifyDocument(timestamped);

        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.signatures())
                .extracting(signature -> signature.signerName())
                .containsExactly(SIGNER_NAME, TSA_NAME);
    }

    @Test
    void rejectsDocumentModifiedAfterTimestamping() throws IOException {
        byte[] timestamped = timestamp(unsignedPdf(), timestampAuthority());
        timestamped[10] ^= 0x01;

        VerificationOutcome outcome = service.verifyDocument(timestamped);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("document timestamp of " + TSA_NAME + " does not match the document content");
    }

    @Test
    void ignoresNonCmsX509RsaSha1Signature() throws IOException {
        Signer signer = validSigner();
        byte[] signed = addSignature(unsignedPdf(), approvalSignature(PDSignature.SUBFILTER_ADBE_X509_RSA_SHA1),
                content -> cms(content.readAllBytes(), signer));

        VerificationOutcome outcome = service.verifyDocument(signed);

        assertThat(outcome.verified()).isFalse();
        assertThat(outcome.message()).contains("does NOT contain a PAdES signature");
    }
}
