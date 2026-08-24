# 테스트 리포트 — upload-server / upload-client

## 요약

| 항목 | 결과 |
|---|---|
| 대상 | `upload-server` (Client Streaming 파일 업로드 gRPC 서비스) |
| 테스트 클래스 | `FileUploadServiceImplTest` |
| 실행 방식 | in-process gRPC (`InProcessServerBuilder`/`InProcessChannelBuilder`) — 소켓 없이 실제 stub → service 직렬화·스트림 경로를 그대로 태움 |
| 테스트 수 | 3 |
| 결과 | **3 passed / 0 failed** |
| 총 소요 시간 | 0.923s |
| 실행 환경 | Windows, JDK 17.0.20 (Temurin), Gradle 8.10.2, hostname `LAPTOP-I1SV7QJJ` |
| 실행 명령 | `./gradlew.bat test --rerun` (upload-server) |

## 테스트 케이스

| 케이스 | 목적 | 소요 시간 | 결과 |
|---|---|---|---|
| 업로드한 파일이 서버에 원본 그대로 저장된다 | 소용량 파일 스트리밍의 정확성(청크 조립 → 디스크 저장 → 원본과 바이트 단위 일치) 검증 | 0.038s | PASS |
| FileInfo 없이 chunk만 보내면 실패 응답을 반환한다 | 프로토콜 오용(메타데이터 누락) 시 서버가 예외 없이 `success=false`를 반환하는지 검증 | 0.010s | PASS |
| 대용량 파일을 다수의 청크로 스트리밍해도 허용 시간 내에 완료된다 | **성능** — 20MB를 64KB 청크(총 320개)로 분할 전송했을 때 정확성 + 처리 시간 검증 | 0.873s | PASS |

## 성능 테스트 상세

- **입력**: 20MB(20,971,520 bytes), 64KB 단위로 분할 → 총 320개 청크를 하나의 Client Streaming RPC로 전송
- **측정**: `stub.uploadFile(flow)` 호출 시작부터 `UploadResponse` 수신까지 걸린 시간(`kotlin.system.measureTimeMillis`)
- **결과**: **20MB를 391ms에 처리 (약 51.15 MB/s)**
- **정확성**: 응답의 `size`가 원본 크기(20,971,520)와 일치, 디스크에 저장된 파일 크기도 동일함을 확인
- **통과 기준**: 5,000ms 이내 완료 (하드 어서션) — 여유 있게 통과. 처리량 수치는 하드 기준이 아니라 회귀 비교용으로 로그에 남김

> in-process 채널(실제 OS 소켓/네트워크 스택을 거치지 않음)로 측정한 수치이므로, 실제 두 프로세스 간 TCP/HTTP2 통신에서는 네트워크 오버헤드만큼 처리량이 낮아질 수 있음. 이 테스트의 목적은 "서비스 로직 자체가 대용량 스트림 처리에서 병목이나 예외 없이 동작하는가"를 자동화된 방식으로 반복 검증하는 것.

## End-to-End 수동 검증 (분리된 두 프로세스)

자동화 테스트와 별개로, 실제 배포 형태와 동일하게 두 프로세스를 독립적으로 기동해 확인함.

1. `upload-server`를 별도 프로세스로 포트 9090에 기동
2. `upload-client`를 별도 프로세스(포트 8081)로 기동 — 기동 시 `sample.txt`를 청크로 스트리밍 업로드
3. 클라이언트 로그: `>>> gRPC upload response: success=true, message=Uploaded sample.txt, size=255`
4. 서버에 저장된 `upload-server/uploads/sample.txt`가 원본과 바이트 단위로 동일함을 `diff`로 확인

## 재현 방법

```bash
# 단위/성능 테스트
cd upload-server
./gradlew.bat test --rerun

# 두 프로세스 실제 통신 확인
cd upload-server && ./gradlew.bat bootRun    # 터미널 1
cd upload-client && ./gradlew.bat bootRun    # 터미널 2 (서버가 뜬 후)
```

테스트 결과 원본 XML: `upload-server/build/test-results/test/TEST-com.saebak.upload.server.FileUploadServiceImplTest.xml` (빌드 산출물이라 Git에는 포함하지 않음, 로컬에서 `./gradlew test` 실행 시 재생성됨)
