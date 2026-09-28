package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.SignatureDTO;
import com.example.digitalsignchecker.application.dto.VerificationOutcome;
import com.example.digitalsignchecker.domain.enums.CertificateValidityStatus;
import com.example.digitalsignchecker.domain.enums.DocumentType;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignerDigestMismatchException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.bouncycastle.util.Store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class PdfVerifyService implements DocumentVerifyStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PdfVerifyService.class);

    private static final COSName DOCUMENT_TIMESTAMP_SUBFILTER = COSName.getPDFName("ETSI.RFC3161");
    private static final COSName REFERENCE = COSName.getPDFName("Reference");
    private static final COSName TRANSFORM_METHOD = COSName.getPDFName("TransformMethod");
    private static final COSName TRANSFORM_PARAMS = COSName.getPDFName("TransformParams");
    private static final COSName DOC_MDP = COSName.getPDFName("DocMDP");
    private static final COSName PERMISSION = COSName.getPDFName("P");
    // ISO 32000-1, 12.8.2.2: P defaults to 2 when absent.
    private static final int DEFAULT_DOC_MDP_PERMISSION = 2;

    private static final Set<COSName> VALID_PADES_SUBFILTERS = Set.of(
            COSName.ADBE_PKCS7_DETACHED,
            COSName.getPDFName("ETSI.CAdES.detached"),
            COSName.ADBE_PKCS7_SHA1,
            DOCUMENT_TIMESTAMP_SUBFILTER
    );

    @Override
    public DocumentType supportedType() {
        return DocumentType.PDF;
    }

    @Override
    public VerificationOutcome verifyDocument(byte[] documentBytes) {

        if (documentBytes == null || documentBytes.length == 0) {
            return new VerificationOutcome(false, "The document data is empty.", List.of());
        }

        List<SignatureDTO> signatureDTOs = new ArrayList<>();

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(documentBytes), MemoryUsageSetting.setupMainMemoryOnly())) {
            List<PDSignature> signatures = document.getSignatureDictionaries().stream()
                    .filter(this::isPadesSignature)
                    .toList();

            if (signatures.isEmpty()) {
                return new VerificationOutcome(false, "The document does NOT contain a PAdES signature.", signatureDTOs);
            }

            List<String> problems = new ArrayList<>();
            // Earlier signatures legitimately cover only a prefix of the file (incremental updates),
            // so it is enough that one of them covers the whole document.
            boolean entireDocumentSigned = false;

            for (PDSignature signature : signatures) {
                boolean coversEntireDocument = isEntireDocumentSigned(signature, documentBytes);
                entireDocumentSigned |= coversEntireDocument;
                if (!coversEntireDocument && forbidsChangesAfterSigning(signature)) {
                    problems.add("the document was modified after a certification signature that forbids any changes");
                }

                if (DOCUMENT_TIMESTAMP_SUBFILTER.getName().equals(signature.getSubFilter())) {
                    verifyDocumentTimestamp(signature, documentBytes, signatureDTOs, problems);
                } else {
                    verifySignature(signature, documentBytes, signatureDTOs, problems);
                }
            }

            if (!entireDocumentSigned) {
                problems.add("the signatures do NOT cover the entire document");
            }

            if (problems.isEmpty()) {
                return new VerificationOutcome(true, "The document contains a valid PAdES signature.", signatureDTOs);
            }

            return new VerificationOutcome(
                    false,
                    "The document contains an invalid PAdES signature: " + String.join("; ", problems) + ".",
                    signatureDTOs
            );
        } catch (Exception exception) {
            logger.error("Document validation error", exception);
            return new VerificationOutcome(false, "Validation error: " + exception.getMessage(), signatureDTOs);
        }
    }

    private void verifySignature(
            PDSignature signature,
            byte[] documentBytes,
            List<SignatureDTO> signatureDTOs,
            List<String> problems
    ) throws IOException, CertificateException {

        CMSSignedData signedData;
        try {
            signedData = new CMSSignedData(
                    new CMSProcessableByteArray(signature.getSignedContent(documentBytes)),
                    signature.getContents(documentBytes)
            );
        } catch (CMSException exception) {
            problems.add("malformed signature: " + exception.getMessage());
            return;
        }

        Store<X509CertificateHolder> certificates = signedData.getCertificates();

        for (SignerInformation signer : signedData.getSignerInfos().getSigners()) {
            Collection<X509CertificateHolder> matches = certificates.getMatches(signer.getSID());
            if (matches.isEmpty()) {
                problems.add("the signer certificate is missing");
                continue;
            }

            X509CertificateHolder holder = matches.iterator().next();
            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
            String signerName = cert.getSubjectX500Principal().getName();

            signatureDTOs.add(new SignatureDTO(
                    signerName,
                    cert.getIssuerX500Principal().getName(),
                    getSigningTime(signer, signature)
            ));

            try {
                if (!signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(holder))) {
                    problems.add("the signature of " + signerName + " does not match the document content");
                }
            } catch (CMSSignerDigestMismatchException exception) {
                problems.add("the signature of " + signerName + " does not match the document content");
            } catch (CMSException | OperatorCreationException | CertificateException exception) {
                problems.add("the signature of " + signerName + " could not be verified: " + exception.getMessage());
            }

            switch (getCertificateValidityStatus(cert)) {
                case VALID -> {}
                case EXPIRED -> problems.add("the certificate of " + signerName + " has expired");
                case NOT_YET_VALID -> problems.add("the certificate of " + signerName + " is not yet valid");
            }
        }
    }

    private void verifyDocumentTimestamp(
            PDSignature signature,
            byte[] documentBytes,
            List<SignatureDTO> signatureDTOs,
            List<String> problems
    ) throws IOException, CertificateException {

        TimeStampToken token;
        try {
            token = new TimeStampToken(new CMSSignedData(signature.getContents(documentBytes)));
        } catch (CMSException | TSPException exception) {
            problems.add("malformed document timestamp: " + exception.getMessage());
            return;
        }

        Collection<X509CertificateHolder> matches = token.getCertificates().getMatches(token.getSID());
        if (matches.isEmpty()) {
            problems.add("the timestamp authority certificate is missing");
            return;
        }

        X509CertificateHolder holder = matches.iterator().next();
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);
        String tsaName = cert.getSubjectX500Principal().getName();
        TimeStampTokenInfo info = token.getTimeStampInfo();

        signatureDTOs.add(new SignatureDTO(tsaName, cert.getIssuerX500Principal().getName(), info.getGenTime().toInstant()));

        try {
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(holder));

            DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder().build().get(info.getHashAlgorithm());
            digestCalculator.getOutputStream().write(signature.getSignedContent(documentBytes));

            if (!Arrays.equals(digestCalculator.getDigest(), info.getMessageImprintDigest())) {
                problems.add("the document timestamp of " + tsaName + " does not match the document content");
            }
        } catch (TSPException | OperatorCreationException | CertificateException exception) {
            problems.add("the document timestamp of " + tsaName + " could not be verified: " + exception.getMessage());
        }
    }

    // PAdES baseline forbids the CMS signing-time attribute and uses /M instead, so both sources are legitimate.
    private Instant getSigningTime(SignerInformation signer, PDSignature signature) {
        AttributeTable signedAttributes = signer.getSignedAttributes();
        Attribute signingTime = signedAttributes != null ? signedAttributes.get(CMSAttributes.signingTime) : null;

        if (signingTime != null) {
            return Time.getInstance(signingTime.getAttrValues().getObjectAt(0)).getDate().toInstant();
        }

        return signature.getSignDate() != null ? signature.getSignDate().toInstant() : null;
    }

    private boolean isPadesSignature(PDSignature signature) {

        if (signature == null) {
            return false;
        }

        String subFilterName = signature.getSubFilter();
        if (subFilterName != null) {
            COSName subFilter = COSName.getPDFName(subFilterName);
            return VALID_PADES_SUBFILTERS.contains(subFilter);
        }

        return false;
    }

    // Only DocMDP P=1 is enforced: P=2/3 allow form filling and annotations,
    // which would require comparing revisions object by object.
    private boolean forbidsChangesAfterSigning(PDSignature signature) {
        if (!(signature.getCOSObject().getDictionaryObject(REFERENCE) instanceof COSArray references)) {
            return false;
        }

        for (int i = 0; i < references.size(); i++) {
            if (references.getObject(i) instanceof COSDictionary reference
                    && DOC_MDP.equals(reference.getCOSName(TRANSFORM_METHOD))
                    && reference.getDictionaryObject(TRANSFORM_PARAMS) instanceof COSDictionary params) {
                return params.getInt(PERMISSION, DEFAULT_DOC_MDP_PERMISSION) == 1;
            }
        }

        return false;
    }

    private boolean isEntireDocumentSigned(PDSignature signature, byte[] documentBytes) {
        try {
            int[] byteRange = signature.getByteRange();
            return byteRange != null && byteRange.length == 4 && (byteRange[2] + byteRange[3]) == documentBytes.length;
        } catch (Exception exception) {
            logger.warn("Signature range could not be verified", exception);
            return false;
        }
    }

    private CertificateValidityStatus getCertificateValidityStatus(X509Certificate certificate) {
        try {
            certificate.checkValidity();
            return CertificateValidityStatus.VALID;
        } catch (CertificateExpiredException e) {
            return CertificateValidityStatus.EXPIRED;
        } catch (CertificateNotYetValidException e) {
            return CertificateValidityStatus.NOT_YET_VALID;
        }
    }
}
