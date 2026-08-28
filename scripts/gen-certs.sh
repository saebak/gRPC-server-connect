#!/usr/bin/env bash
#
# 로컬 개발/테스트용 자체 서명 TLS 인증서를 생성한다.
#   - upload-server/certs/server.crt , server.key  (서버가 제시하는 인증서 + 개인키)
#   - upload-client/certs/server.crt               (클라이언트가 신뢰하는 루트, server.crt 복사본)
#
# 개인키는 절대 커밋하지 않는다(.gitignore). 새로 clone 했거나 인증서가 없으면 이 스크립트를 실행한다.
#
#   ./scripts/gen-certs.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_DIR="$ROOT/upload-server/certs"
CLIENT_DIR="$ROOT/upload-client/certs"

mkdir -p "$SERVER_DIR" "$CLIENT_DIR"

# Git Bash(MSYS)에서 "/CN=..." 가 Windows 경로로 변환되는 것을 막는다.
export MSYS2_ARG_CONV_EXCL='*'

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout "$SERVER_DIR/server.key" \
  -out "$SERVER_DIR/server.crt" \
  -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

cp "$SERVER_DIR/server.crt" "$CLIENT_DIR/server.crt"

echo "생성 완료:"
echo "  $SERVER_DIR/server.crt"
echo "  $SERVER_DIR/server.key"
echo "  $CLIENT_DIR/server.crt"
