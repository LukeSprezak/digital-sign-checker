package com.example.digitalsignchecker.support;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// Generates PDFs signed with throwaway certificates issued by an in-memory test CA.
public final class TestPdfs {

    public static final String CA_NAME = "CN=Test CA";
    public static final String SIGNER_NAME = "CN=Test Signer";
    public static final String TSA_NAME = "CN=Test TSA";
    private static final AtomicLong SERIAL = new AtomicLong(1);
    private static final KeyPair CA_KEYS = keyPair();
    private static final X509Certificate CA_CERTIFICATE =
            certificate(CA_NAME, CA_KEYS.getPublic(), Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)), List.of());

    private TestPdfs() {}

    public record Signer(PrivateKey key, X509Certificate certificate) {}

    public static Signer validSigner() {
        return signer(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(365)));
    }

    public static Signer signer(Instant notBefore, Instant notAfter) {
        KeyPair keys = keyPair();
        return new Signer(keys.getPrivate(), certificate(SIGNER_NAME, keys.getPublic(), notBefore, notAfter, List.of()));
    }

    // RFC 3161 requires the TSA certificate to carry a critical extended key usage of id-kp-timeStamping only.
    public static Signer timestampAuthority() {
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

    public static byte[] unsignedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] sign(byte[] pdf, Signer signer) throws IOException {
        return addSignature(pdf, approvalSignature(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED), content -> cms(content.readAllBytes(), signer));
    }

    public static byte[] certify(byte[] pdf, Signer signer, int permission) throws IOException {
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

    public static byte[] timestamp(byte[] pdf, Signer tsa) throws IOException {
        PDSignature signature = new PDSignature();
        signature.setType(COSName.getPDFName("DocTimeStamp"));
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(COSName.getPDFName("ETSI.RFC3161"));

        return addSignature(pdf, signature, content -> timestampToken(content.readAllBytes(), tsa));
    }

    public static PDSignature approvalSignature(COSName subFilter) {
        PDSignature signature = new PDSignature();
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(subFilter);
        signature.setSignDate(Calendar.getInstance());
        return signature;
    }

    public static byte[] addSignature(byte[] pdf, PDSignature signature, SignatureInterface signing) throws IOException {
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

    public static byte[] cms(byte[] content, Signer signer) throws IOException {
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
