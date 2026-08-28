# 테스트 케이스 명세 — upload-server / upload-client

별도 표기가 없으면 각 케이스는 in-process gRPC(`InProcessServerBuilder`/`InProcessChannelBuilder`)로 실제 stub → service 직렬화·스트림 경로를 그대로 태우되 소켓은 거치지 않는다(`TlsIntegrationTest`만 실제 소켓 사용). 실행 방법과 최근 실행 결과는 [`docs/test-report.md`](test-report.md) 참고.

## upload-server — `FileUploadServiceImplTest`

### UploadFile (Client Streaming)

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 1 | 업로드한 파일이 서버에 원본 그대로 저장된다 | `FileInfo("small.txt")` + 청크 8바이트씩 분할 전송 | `success=true`, 응답 `size`가 원본 크기와 일치, 디스크 파일이 원본과 바이트 단위로 동일 |
| 2 | FileInfo 없이 chunk만 보내면 FAILED_PRECONDITION을 반환한다 | `FileInfo` 없이 `chunk`만 바로 전송 | `Status.Code.FAILED_PRECONDITION` |
| 3 | 빈 파일명으로 업로드하면 INVALID_ARGUMENT를 반환한다 | `FileInfo(filename="")` 전송 | `Status.Code.INVALID_ARGUMENT` |
| 4 | 청크 없이 FileInfo만 보내면 크기 0인 빈 파일이 정상 업로드된다 | `FileInfo`만 보내고 `chunk`는 하나도 보내지 않음 | `success=true`, `size=0`, 디스크에 크기 0인 파일 생성 |
| 5 | 업로드 중 커넥션이 끊기면 서버에 남은 부분 파일을 정리한다 | `FileInfo` + 일부 `chunk` 전송 후 스트림에서 예외 발생(연결 끊김 시뮬레이션) | 예외가 전파되고, 디스크에 부분 파일이 남지 않음(정리됨) |
| 6 | 허용 크기를 초과하면 RESOURCE_EXHAUSTED를 반환하고 부분 파일을 남기지 않는다 | `maxUploadBytes=10`으로 설정한 서버에 10바이트 초과 청크 전송 | `Status.Code.RESOURCE_EXHAUSTED`, 부분 파일 미생성 |
| 7 | 대용량 파일을 다수의 청크로 스트리밍해도 허용 시간 내에 완료된다 (성능) | 20MB를 64KB씩(총 320청크) 전송 | `success=true`, 크기 일치, 5,000ms 이내 완료 |

### DownloadFile (Server Streaming)

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 8 | 업로드한 파일을 다운로드하면 원본과 동일한 바이트를 스트림으로 받는다 | 사전에 `roundtrip.txt` 업로드 후 동일 파일명으로 다운로드 요청 | 스트림으로 받은 청크를 이어붙인 바이트가 원본과 동일 |
| 9 | 존재하지 않는 파일을 다운로드하면 NOT_FOUND를 반환한다 | 업로드된 적 없는 파일명으로 요청 | `Status.Code.NOT_FOUND` |
| 10 | 빈 파일명으로 다운로드를 요청하면 INVALID_ARGUMENT를 반환한다 | `filename=""`으로 요청 | `Status.Code.INVALID_ARGUMENT` |

### ListFiles (Unary)

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 11 | 업로드된 파일이 없으면 빈 목록을 반환한다 | 업로드 이력 없는 서버에 `ListFiles` 요청 | `filesList`가 빈 리스트 |
| 12 | 업로드된 파일들의 이름과 크기를 이름순으로 반환한다 | `b.txt`(6바이트) 업로드 후 `a.txt`(5바이트) 업로드, 순서 뒤바꿔서 전송 | `filesList`가 `[a.txt(5), b.txt(6)]` 순 — 업로드 순서가 아니라 이름순 정렬 |

### 운영 인터셉터 / 헬스체크 — `GrpcOpsTest`

`FileUploadServiceImpl`을 `AuthServerInterceptor`로 감싼 in-process 서버와, `HealthStatusManager`가 등록한 `grpc.health.v1.Health` 서비스를 함께 띄워 검증한다.

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 13 | 유효한 토큰이면 RPC가 통과한다 | `authorization: Bearer dev-secret-token` 부착 후 `UploadFile` 호출 | `success=true` |
| 14 | 토큰이 없으면 UNAUTHENTICATED를 반환한다 | `authorization` 메타데이터 없이 호출 | `Status.Code.UNAUTHENTICATED` |
| 15 | 토큰이 틀리면 UNAUTHENTICATED를 반환한다 | `Bearer wrong-token` 부착 후 호출 | `Status.Code.UNAUTHENTICATED` |
| 16 | 헬스체크는 토큰 없이도 SERVING을 반환한다 | 토큰 없이 `grpc.health.v1.Health/Check`(`service=upload.FileUploadService`) 호출 | `ServingStatus.SERVING` — 인증 예외 서비스 확인 |

### TLS — `TlsIntegrationTest`

in-process가 아니라 실제 TCP 소켓(`NettyServerBuilder`/`NettyChannelBuilder`)을 사용한다. 인증서(`upload-server/certs/`)가 없으면 `Assumptions.assumeTrue`로 건너뛴다.

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 17 | TLS 채널로 RPC를 호출할 수 있다 | `useTransportSecurity(server.crt, server.key)` 서버 + 해당 인증서를 신뢰하는 TLS 채널, authority=`localhost` | 핸드셰이크 성립 후 `ListFiles`가 정상 응답 |

## upload-client — `FileUploadClientTest`

`FileUploadClient`가 stub 호출을 감싸 gRPC 에러를 사람이 읽을 수 있는 로그로 변환하고, 예외 대신 `null`을 반환하는지 검증한다. 서버 역할은 `FakeFileUploadService`(테스트 전용 더미 구현체)가 대신하며, 응답/에러를 필드에 미리 세팅해두고 in-process로 띄운다.

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 1 | 업로드가 성공하면 서버 응답을 그대로 반환한다 | `FakeFileUploadService.uploadResponse`에 성공 응답 세팅 | `upload()`가 해당 `UploadResponse`를 그대로 반환 |
| 2 | 업로드가 RESOURCE_EXHAUSTED로 실패하면 예외 대신 null을 반환한다 | `FakeFileUploadService.uploadError = RESOURCE_EXHAUSTED` | `upload()`가 예외를 던지지 않고 `null` 반환 |
| 3 | 목록 조회가 성공하면 파일 목록을 반환한다 | `FakeFileUploadService.listResponse`에 `FileEntry` 1건 세팅 | `listFiles()`가 해당 목록을 그대로 반환 |
| 4 | 목록 조회가 실패하면 예외 대신 null을 반환한다 | `FakeFileUploadService.listError = UNAVAILABLE` | `listFiles()`가 `null` 반환 |
| 5 | 다운로드가 성공하면 청크를 이어붙인 바이트를 반환한다 | `FakeFileUploadService.downloadChunks = ["hel", "lo"]` | `download()`가 `"hello"`를 반환 |
| 6 | 다운로드가 NOT_FOUND로 실패하면 예외 대신 null을 반환한다 | `FakeFileUploadService.downloadError = NOT_FOUND` | `download()`가 `null` 반환 |

### 인증 인터셉터 — `AuthClientInterceptorTest`

`AuthClientInterceptor`를 채널에 끼우고, 서버 쪽 헤더 캡처 인터셉터로 부착된 메타데이터를 확인한다.

| # | 케이스 | 사전 조건 / 입력 | 기대 결과 |
|---|---|---|---|
| 7 | 인터셉터가 Bearer 토큰을 붙인다 | `AuthClientInterceptor("dev-secret-token")`로 감싼 stub으로 `listFiles()` 호출 | 서버가 수신한 `authorization` 헤더가 `Bearer dev-secret-token` |

## 커버리지 밖 (의도적으로 남겨둔 것)

- **실제 두 프로세스 간 TCP/HTTP2 통신**: 대부분의 자동화 테스트는 in-process 채널이라 소켓을 거치지 않음(TLS 테스트만 실제 소켓 사용). `docs/test-report.md`의 End-to-End 수동 검증 절차로 보완.
- **인증서 만료·불일치 SAN·mTLS**: TLS 테스트는 정상 경로(핸드셰이크 성공)만 확인. 실패 경로는 다루지 않음.
- **클라이언트 자동화 테스트에서 실제 파일 I/O**: `FileUploadClient.upload()`는 `InputStream`을 받으므로 테스트에서는 `ByteArrayInputStream`을 사용, 디스크 파일을 거치지 않음.
- **동시 다중 클라이언트/업로드 경쟁 상태**: 현재 테스트는 순차 시나리오만 다룸.
