# 테스트 리포트 — upload-server / upload-client

## 요약

| 항목 | upload-server | upload-client |
|---|---|---|
| 테스트 클래스 | `FileUploadServiceImplTest`, `GrpcOpsTest`, `TlsIntegrationTest` | `FileUploadClientTest`, `AuthClientInterceptorTest` |
| 실행 방식 | 대부분 in-process gRPC (`InProcessServerBuilder`/`InProcessChannelBuilder`) — 소켓 없이 실제 stub → service 직렬화·스트림 경로를 그대로 태움. `TlsIntegrationTest`만 실제 TCP 소켓(`NettyServerBuilder`) 사용 | in-process gRPC. 서버 역할은 `FakeFileUploadService`(테스트 전용 더미 구현)가 대신함 — 두 프로젝트가 독립 Gradle 프로젝트라 실제 `FileUploadServiceImpl`을 참조할 수 없기 때문 |
| 테스트 수 | 17 (12 + 4 + 1) | 7 (6 + 1) |
| 결과 | **17 passed / 0 failed** | **7 passed / 0 failed** |
| 총 소요 시간 | 약 4.4s | 약 0.7s |
| 실행 환경 | Windows, JDK 17.0.20 (Temurin), Gradle 8.10.2, hostname `LAPTOP-I1SV7QJJ` | 동일 |
| 실행 명령 | `./gradlew.bat test --rerun` (upload-server) | `./gradlew.bat test --rerun` (upload-client) |

케이스별 목적과 검증 내용은 [`docs/test-cases.md`](test-cases.md) 참고.

> `TlsIntegrationTest`는 `upload-server/certs/`의 자체 서명 인증서를 사용한다. 인증서가 없으면(`scripts/gen-certs.sh` 미실행) `Assumptions.assumeTrue`로 **skip** 처리되며 실패로 잡히지 않는다.

## 성능 테스트 상세 (upload-server)

- **대상**: `대용량 파일을 다수의 청크로 스트리밍해도 허용 시간 내에 완료된다`
- **입력**: 20MB(20,971,520 bytes), 64KB 단위로 분할 → 총 320개 청크를 하나의 Client Streaming RPC로 전송
- **측정**: `stub.uploadFile(flow)` 호출 시작부터 `UploadResponse` 수신까지 걸린 시간(`kotlin.system.measureTimeMillis`)
- **결과**: 20MB를 408ms에 처리 (약 49MB/s)
- **정확성**: 응답의 `size`가 원본 크기(20,971,520)와 일치, 디스크에 저장된 파일 크기도 동일함을 확인
- **통과 기준**: 5,000ms 이내 완료 (하드 어서션) — 여유 있게 통과. 처리량 수치는 하드 기준이 아니라 회귀 비교용으로 로그에 남김

> in-process 채널(실제 OS 소켓/네트워크 스택을 거치지 않음)로 측정한 수치이므로, 실제 두 프로세스 간 TCP/HTTP2 통신에서는 네트워크 오버헤드만큼 처리량이 낮아질 수 있음. 이 테스트의 목적은 "서비스 로직 자체가 대용량 스트림 처리에서 병목이나 예외 없이 동작하는가"를 자동화된 방식으로 반복 검증하는 것.
>
> `허용 크기를 초과하면 RESOURCE_EXHAUSTED...` 케이스가 3.05s로 유독 오래 걸리는데, 이는 별도 in-process 서버/채널을 새로 띄우고 종료하는 데 드는 고정 비용(JVM/gRPC 초기화)이 대부분이며 서비스 로직 자체의 성능과는 무관함.

## End-to-End 수동 검증 (분리된 두 프로세스)

자동화 테스트와 별개로, 실제 배포 형태와 동일하게 두 프로세스를 독립적으로 기동해 확인함.

1. `upload-server`를 별도 프로세스로 포트 9090에 기동
2. `upload-client`를 별도 프로세스(포트 8081)로 기동 — 기동 시 `sample.txt`를 청크로 스트리밍 업로드 → 목록 조회 → 다운로드 순으로 실행
3. 클라이언트 로그:
   ```
   >>> gRPC health response: upload.FileUploadService=SERVING
   >>> gRPC upload response: success=true, message=Uploaded sample.txt, size=255
   >>> gRPC list response: sample.txt(255B)
   >>> gRPC download response: downloaded 255 bytes, matchesOriginal=true
   ```
4. 서버 로그에 `LoggingServerInterceptor`가 각 RPC의 `--> ` / `<-- ... OK (n ms)` 를 남기고, 클라이언트→서버 통신이 TLS로 이뤄짐(`negotiation-type: TLS`, 자체 서명 인증서 신뢰)
5. 서버에 저장된 `upload-server/uploads/sample.txt`가 원본과 바이트 단위로 동일함을 `diff`로 확인

## 재현 방법

```bash
# (최초 1회) TLS 테스트용 인증서 생성
./scripts/gen-certs.sh

# upload-server 단위/성능 테스트
cd upload-server
./gradlew.bat test --rerun

# upload-client 단위 테스트
cd upload-client
./gradlew.bat test --rerun

# 두 프로세스 실제 통신 확인
cd upload-server && ./gradlew.bat bootRun    # 터미널 1
cd upload-client && ./gradlew.bat bootRun    # 터미널 2 (서버가 뜬 후)
```

테스트 결과 원본 XML은 빌드 산출물이라 Git에는 포함하지 않음, 로컬에서 `./gradlew test` 실행 시 아래 경로에 재생성됨:

- `upload-server/build/test-results/test/TEST-com.saebak.upload.server.FileUploadServiceImplTest.xml`
- `upload-server/build/test-results/test/TEST-com.saebak.upload.server.GrpcOpsTest.xml`
- `upload-server/build/test-results/test/TEST-com.saebak.upload.server.TlsIntegrationTest.xml`
- `upload-client/build/test-results/test/TEST-com.saebak.upload.client.FileUploadClientTest.xml`
- `upload-client/build/test-results/test/TEST-com.saebak.upload.client.AuthClientInterceptorTest.xml`
