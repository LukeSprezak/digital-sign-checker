CREATE TABLE document
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    uuid BINARY(16) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    type ENUM ('PDF','XML') NOT NULL,
    status ENUM ('PENDING','VERIFIED','INVALID','ERROR') NOT NULL,
    content LONGBLOB NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    deleted BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_document_uuid UNIQUE (uuid)
);

CREATE TABLE signature
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    signer_name VARCHAR(255),
    certificate_issuer VARCHAR(255),
    signing_time DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_signature_document FOREIGN KEY (document_id) REFERENCES document (id)
);

CREATE TABLE verify_result
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    uuid BINARY(16) NOT NULL,
    document_id BIGINT NOT NULL,
    status ENUM ('PENDING','IN_PROGRESS','COMPLETED','INVALID','ERROR') NOT NULL,
    verified BIT(1) NOT NULL,
    message TEXT NOT NULL,
    verification_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_verify_result_uuid UNIQUE (uuid),
    CONSTRAINT fk_verify_result_document FOREIGN KEY (document_id) REFERENCES document (id)
);
