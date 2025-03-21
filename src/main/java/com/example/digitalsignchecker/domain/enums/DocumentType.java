package com.example.digitalsignchecker.domain.enums;

public enum DocumentType {
    PDF, XML, UNKNOWN;

    public static DocumentType fromString(String value) {
        try {
            return DocumentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UNKNOWN;
        }
    }
}
