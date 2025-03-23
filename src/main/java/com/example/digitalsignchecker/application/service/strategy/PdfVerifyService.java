package com.example.digitalsignchecker.application.service.strategy;

import com.example.digitalsignchecker.application.dto.SignatureDTO;
import com.example.digitalsignchecker.application.dto.VerifyResultDTO;
import com.example.digitalsignchecker.domain.enums.CertificateValidityStatus;
import com.example.digitalsignchecker.domain.service.DocumentVerifyStrategy;
import org.apache.pdfbox.cos.COSName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.util.Store;

import java.io.ByteArrayInputStream;
import java.security.cert.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Service
public class PdfVerifyService implements DocumentVerifyStrategy {

    private static final Logger logger = LoggerFactory.getLogger(PdfVerifyService.class);

    private static final Set<COSName> VALID_PADES_SUBFILTERS = Set.of(
            COSName.ADBE_X509_RSA_SHA1,
            COSName.ADBE_PKCS7_DETACHED,
            COSName.getPDFName("ETSI.CAdES.detached"),
            COSName.ADBE_PKCS7_SHA1
    );

    @Override
    public VerifyResultDTO verifyDocument(byte[] documentBytes) {

        if (documentBytes == null || documentBytes.length == 0) {
            return VerifyResultDTO.fromVerification(false, "The document data is empty.", List.of());
        }

        List<SignatureDTO> signatureDTOs = new ArrayList<>();
        boolean partialSignature = false;
        String validationMessage = "The document contains a valid PAdES signature.";

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(documentBytes), MemoryUsageSetting.setupMainMemoryOnly())) {
            List<PDSignature> signatures = document.getSignatureDictionaries();

            if (signatures.isEmpty()) {
                return VerifyResultDTO.fromVerification(false, "The document does NOT contain a PAdES signature.", signatureDTOs);
            }

            for (PDSignature signature : signatures) {
                if (!isPadesSignature(signature)) continue;

                byte[] signatureContent = signature.getContents(new ByteArrayInputStream(documentBytes));
                if (signatureContent == null || signatureContent.length == 0) continue;

                CMSSignedData signedData;
                try {
                    signedData = new CMSSignedData(signatureContent);
                } catch (Exception exception) {
                    logger.error("Signature validation error: {}", exception.getMessage());
                    return VerifyResultDTO.fromVerification(false, "Signature validation error: " + exception.getMessage(), signatureDTOs);
                }

                Store<X509CertificateHolder> certsStore = signedData.getCertificates();
                Collection<X509CertificateHolder> certificateHolders = certsStore.getMatches(null);
                JcaX509CertificateConverter certificateConverter = new JcaX509CertificateConverter();

                for (X509CertificateHolder holder : certificateHolders) {
                    X509Certificate cert;
                    try {
                        cert = certificateConverter.getCertificate(holder);
                    } catch (Exception exception) {
                        logger.warn("Failed to convert certificate: {}", exception.getMessage());
                        continue;
                    }

                    String signerName = cert.getSubjectX500Principal().getName();
                    String certificateIssuer = cert.getIssuerX500Principal().getName();
                    Instant signingTime = signature.getSignDate() != null ? signature.getSignDate().toInstant() : null;
                    boolean isCompleteDocumentSigned = isEntireDocumentSigned(signature, documentBytes);

                    CertificateValidityStatus validityStatus = getCertificateValidityStatus(cert);
                    String validityMessage = switch (validityStatus) {
                        case VALID -> "";
                        case EXPIRED -> "The document contains a PAdES signature, but the signature certificate has expired.";
                        case NOT_YET_VALID -> "The document contains a PAdES signature, but the signature certificate is not yet valid.";
                    };

                    if (!isCompleteDocumentSigned) {
                        partialSignature = true;
                    }
                    if (!validityMessage.isEmpty()) {
                        validationMessage = validityMessage;
                    }

                    signatureDTOs.add(new SignatureDTO(signerName, certificateIssuer, signingTime));

                    return VerifyResultDTO.fromVerification(
                            true,
                            validationMessage + (partialSignature ? " (Note: the signature does NOT cover the entire document)." : ""),
                            signatureDTOs
                    );
                }
            }
        } catch (Exception exception) {
            logger.error("Document validation error: {}", exception.getMessage());
            return VerifyResultDTO.fromVerification(false, "Validation error: " + exception.getMessage(), signatureDTOs);
        }

        return VerifyResultDTO.fromVerification(false, "The document does NOT contain a valid PAdES signature.", signatureDTOs);
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

    private boolean isEntireDocumentSigned(PDSignature signature, byte[] documentBytes) {
        try {
            int[] byteRange = signature.getByteRange();
            return byteRange != null && byteRange.length == 4 && (byteRange[2] + byteRange[3]) == documentBytes.length;
        } catch (Exception exception) {
            logger.warn("Signature range could not be verified: {}", exception.getMessage());
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
