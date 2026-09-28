#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

LOCAL_CONFIG=src/main/resources/application-local.yaml
if [ ! -f "$LOCAL_CONFIG" ]; then
  cat > "$LOCAL_CONFIG" <<'YAML'
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/digital_sign_checker?createDatabaseIfNotExist=true
    username: root
    password: root

app:
  api-key: local-dev-key
YAML
  echo "Created $LOCAL_CONFIG"
fi

docker compose up -d --wait

echo "MySQL is ready. Start the app with:"
echo "  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local"
