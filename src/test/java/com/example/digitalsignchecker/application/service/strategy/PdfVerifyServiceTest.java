package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;
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
    private static final String TSA_NAME = "CN=Test TSA";
    private static final X509Certificate CA_CERTIFICATE =
            certificate(CA_NAME, CA_KEYS.getPublic(), Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)), List.of());

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

    private record Signer(PrivateKey key, X509Certificate certificate) {}

    private static Signer validSigner() {
        return signer(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)));
    }

    private static Signer signer(Instant notBefore, Instant notAfter) {
        KeyPair keys = keyPair();
        return new Signer(keys.getPrivate(), certificate(SIGNER_NAME, keys.getPublic(), notBefore, notAfter, List.of()));
    }

    // RFC 3161 requires the TSA certificate to carry a critical extended key usage of id-kp-timeStamping only.
    private static Signer timestampAuthority() {
        KeyPair keys = keyPair();
        Extension timeStamping = new Extension(Extension.extendedKeyUsage, true, encode(new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping)));
        return new Signer(keys.getPrivate(), certificate(
                TSA_NAME, keys.getPublic(), Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)), List.of(timeStamping)));
    }

    private static byte[] encode(ExtendedKeyUsage extendedKeyUsage) {
        try {
            return extendedKeyUsage.getEncoded();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
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

    private static X509Certificate certificate(String subject, PublicKey publicKey, Instant notBefore, Instant notAfter, List<Extension> extensions) {
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name(CA_NAME),
                    BigInteger.valueOf(SERIAL.getAndIncrement()),
                    Date.from(notBefore),
                    Date.from(notAfter),
                    new X500Name(subject),
                    publicKey
            );
            for (Extension extension : extensions) {
                builder.addExtension(extension);
            }
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(CA_KEYS.getPrivate())));
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
        return addSignature(pdf, approvalSignature(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED), content -> cms(content.readAllBytes(), signer));
    }

    private static byte[] certify(byte[] pdf, Signer signer, int permission) throws IOException {
        COSDictionary transformParams = new COSDictionary();
        transformParams.setItem(COSName.TYPE, COSName.getPDFName("TransformParams"));
        transformParams.setInt(COSName.P, permission);
        transformParams.setName(COSName.V, "1.2");

        COSDictionary reference = new COSDictionary();
        reference.setItem(COSName.TYPE, COSName.getPDFName("SigRef"));
        reference.setItem(COSName.getPDFName("TransformMethod"), COSName.getPDFName("DocMDP"));
        reference.setItem(COSName.getPDFName("TransformParams"), transformParams);

        COSArray references = new COSArray();
        references.add(reference);

        PDSignature signature = approvalSignature(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
        signature.getCOSObject().setItem(COSName.getPDFName("Reference"), references);

        return addSignature(pdf, signature, content -> cms(content.readAllBytes(), signer));
    }

    private static byte[] timestamp(byte[] pdf, Signer tsa) throws IOException {
        PDSignature signature = new PDSignature();
        signature.setType(COSName.getPDFName("DocTimeStamp"));
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(COSName.getPDFName("ETSI.RFC3161"));

        return addSignature(pdf, signature, content -> timestampToken(content.readAllBytes(), tsa));
    }

    private static PDSignature approvalSignature(COSName subFilter) {
        PDSignature signature = new PDSignature();
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(subFilter);
        signature.setSignDate(Calendar.getInstance());
        return signature;
    }

    private static byte[] addSignature(byte[] pdf, PDSignature signature, SignatureInterface signing) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            document.addSignature(signature, signing);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.saveIncremental(out);
            return out.toByteArray();
        }
    }

    private static byte[] timestampToken(byte[] content, Signer tsa) throws IOException {
        try {
            DigestCalculatorProvider digests = new JcaDigestCalculatorProviderBuilder().build();
            TimeStampTokenGenerator generator = new TimeStampTokenGenerator(
                    new JcaSimpleSignerInfoGeneratorBuilder().build("SHA256withRSA", tsa.key(), tsa.certificate()),
                    digests.get(new DefaultDigestAlgorithmIdentifierFinder().find("SHA-256")),
                    new ASN1ObjectIdentifier("1.2.3.4.5")
            );
            generator.addCertificates(new JcaCertStore(List.of(tsa.certificate())));

            byte[] imprint = MessageDigest.getInstance("SHA-256").digest(content);
            TimeStampRequestGenerator requestGenerator = new TimeStampRequestGenerator();
            // Without certReq the TSA leaves its certificate out of the token and the token cannot be verified.
            requestGenerator.setCertReq(true);
            TimeStampRequest request = requestGenerator.generate(TSPAlgorithms.SHA256, imprint);
            return generator.generate(request, BigInteger.valueOf(SERIAL.getAndIncrement()), new Date()).getEncoded();
        } catch (OperatorCreationException | CertificateEncodingException | TSPException | NoSuchAlgorithmException exception) {
            throw new IOException(exception);
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
