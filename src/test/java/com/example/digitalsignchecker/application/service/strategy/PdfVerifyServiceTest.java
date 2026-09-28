package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PdfVerifyServiceTest {

    private static final String CA_NAME = "CN=Test CA";
    private static final String SIGNER_NAME = "CN=Test Signer";
    private static final AtomicLong SERIAL = new AtomicLong(1);
    private static final KeyPair CA_KEYS = keyPair();
    private static final X509Certificate CA_CERTIFICATE =
            certificate(CA_NAME, CA_KEYS.getPublic(), Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)));

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

    private record Signer(PrivateKey key, X509Certificate certificate) {}

    private static Signer validSigner() {
        return signer(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)));
    }

    private static Signer signer(Instant notBefore, Instant notAfter) {
        KeyPair keys = keyPair();
        return new Signer(keys.getPrivate(), certificate(SIGNER_NAME, keys.getPublic(), notBefore, notAfter));
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static X509Certificate certificate(String subject, PublicKey publicKey, Instant notBefore, Instant notAfter) {
        try {
            return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                    new X500Name(CA_NAME),
                    BigInteger.valueOf(SERIAL.getAndIncrement()),
                    Date.from(notBefore),
                    Date.from(notAfter),
                    new X500Name(subject),
                    publicKey
            ).build(new JcaContentSignerBuilder("SHA256withRSA").build(CA_KEYS.getPrivate())));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] unsignedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] sign(byte[] pdf, Signer signer) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setSignDate(Calendar.getInstance());

            document.addSignature(signature, content -> cms(content.readAllBytes(), signer));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.saveIncremental(out);
            return out.toByteArray();
        }
    }

    private static byte[] cms(byte[] content, Signer signer) throws IOException {
        try {
            CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
            generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                    .build(new JcaContentSignerBuilder("SHA256withRSA").build(signer.key()), signer.certificate()));
            // CA first: a verifier that takes the first certificate from the store would report the CA as the signer.
            generator.addCertificates(new JcaCertStore(List.of(CA_CERTIFICATE, signer.certificate())));
            return generator.generate(new CMSProcessableByteArray(content), false).getEncoded();
        } catch (OperatorCreationException | CertificateEncodingException | CMSException exception) {
            throw new IOException(exception);
        }
    }
}
