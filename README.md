# digital-sign-checker

REST service that verifies PAdES signatures in PDF files. Verification is asynchronous: the upload returns a link where the result appears.

## Requirements

- Java 25
- Docker (local MySQL and Testcontainers in tests)

## Running

1. Setup (creates `src/main/resources/application-local.yaml` if missing and starts MySQL from `docker-compose.yml`):

   ```bash
   ./scripts/setup.sh
   ```

2. Start the application (port 8080, API key `local-dev-key`, Flyway creates the schema):

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

Without the `local` profile the application expects the `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `API_KEY` environment variables.

## Manual testing

Every request requires the `X-API-Key` header.

Upload a document:

```bash
curl -i -H "X-API-Key: local-dev-key" -F "file=@document.pdf" http://localhost:8080/api/documents/upload
```

`202 Accepted` response:

```json
{"link": "/api/documents/status/3f2b8c1e-..."}
```

Verification result:

```bash
curl -H "X-API-Key: local-dev-key" http://localhost:8080/api/documents/status/3f2b8c1e-...
```

```json
{
  "uuid": "3f2b8c1e-...",
  "status": "COMPLETED",
  "verified": true,
  "message": "...",
  "verificationTime": "2026-09-28T17:00:00Z",
  "signatures": [
    {"signerName": "John Smith", "certificateIssuer": "CN=...", "signingTime": "2026-09-01T10:00:00Z"}
  ]
}
```

Statuses: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `INVALID`, `ERROR`.

Sample data:

- any unsigned PDF → `status: INVALID`, `verified: false`, message "does NOT contain a PAdES signature",
- a PAdES-signed PDF (e.g. signed in Adobe Acrobat) → `status: COMPLETED`, `verified: true` and the list of signatures.

List all results:

```bash
curl -H "X-API-Key: local-dev-key" http://localhost:8080/api/verify-results
```

## Automated tests

```bash
./mvnw test
```

Integration tests start MySQL via Testcontainers, so Docker must be running.
