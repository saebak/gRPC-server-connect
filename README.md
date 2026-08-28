# gRPC Server Connect

Kotlin + Spring Boot 기반 gRPC 파일 전송 서비스.
완전히 분리된 두 개의 Spring Boot 프로세스(`upload-server`, `upload-client`)가 **Unary / Client Streaming / Server Streaming** 세 가지 RPC 패턴으로 파일을 주고받는 구현을 담고 있다.

## 개요

- 클라이언트가 파일을 청크 단위로 스트리밍 전송 → 서버가 모든 청크를 받은 뒤 응답 1개를 반환하는 **Client Streaming**(`UploadFile`) 예제
- 요청 1개에 서버가 저장된 파일을 청크로 스트리밍 응답하는 **Server Streaming**(`DownloadFile`) 예제
- 요청/응답이 각각 1개씩인 **Unary**(`ListFiles`) 예제
- 하나의 Git 저장소 안에 독립된 두 개의 Gradle 프로젝트를 두어, 서버/클라이언트가 서로 다른 프로세스·포트에서 실제 네트워크(HTTP/2)로 통신하는 것을 확인하는 데 목적이 있음
- `.proto`로 정의한 계약을 양쪽 프로젝트가 각자 코드 생성하여 사용

## 기술 스택

| 분류 | 기술 |
|---|---|
| 언어 | Kotlin 1.9.25 |
| 프레임워크 | Spring Boot 3.3.4 |
| 통신 | gRPC 1.63.0 (HTTP/2 + Protobuf) |
| gRPC-Spring 통합 | `net.devh:grpc-spring-boot-starter` 3.1.0.RELEASE (`@GrpcService`, `@GrpcClient`, `@GrpcGlobal*Interceptor`) |
| 헬스체크 | `io.grpc:grpc-services` 1.63.0 (`grpc.health.v1.Health`) |
| Kotlin gRPC | `grpc-kotlin-stub` 1.4.1 (코루틴 기반 stub) |
| 직렬화 | Protocol Buffers 3.25.3 |
| 빌드 | Gradle 8.10.2 (Kotlin DSL, `com.google.protobuf` 플러그인) |
| 런타임 | JDK 17 |

## 프로젝트 구조

```
gRPC-server-connect/
├── scripts/gen-certs.sh    # 로컬 TLS 자체 서명 인증서 생성 스크립트
├── upload-server/          # 독립 Gradle 프로젝트 — gRPC 서버
│   ├── certs/              # server.crt / server.key (gen-certs.sh 산출물, git 제외)
│   └── src/main/
│       ├── proto/file_upload.proto
│       ├── kotlin/com/saebak/upload/
│       │   ├── UploadServerApplication.kt
│       │   └── server/
│       │       ├── FileUploadServiceImpl.kt
│       │       ├── LoggingServerInterceptor.kt   (@GrpcGlobalServerInterceptor — RPC 시작/종료 로깅)
│       │       ├── AuthServerInterceptor.kt      (@GrpcGlobalServerInterceptor — Bearer 토큰 검증)
│       │       └── GrpcHealthConfig.kt           (grpc.health.v1.Health 상태 SERVING 설정)
│       └── resources/application.yml   (grpc.server.port: 9090, TLS, upload.auth.token)
└── upload-client/          # 독립 Gradle 프로젝트 — gRPC 클라이언트
    ├── certs/              # server.crt (신뢰 앵커, gen-certs.sh 산출물, git 제외)
    └── src/main/
        ├── proto/file_upload.proto      (upload-server와 동일 파일)
        ├── kotlin/com/saebak/upload/
        │   ├── UploadClientApplication.kt
        │   └── client/
        │       ├── FileUploadClient.kt          (stub 호출 + 상태 코드별 에러 처리)
        │       ├── FileUploadClientRunner.kt    (실행 진입점 — 헬스체크 후 업로드/목록/다운로드)
        │       ├── AuthClientInterceptor.kt     (@GrpcGlobalClientInterceptor — Bearer 토큰 부착)
        │       └── LoggingClientInterceptor.kt  (@GrpcGlobalClientInterceptor — RPC 종료 로깅)
        └── resources/
            ├── application.yml           (server.port: 8081, grpc.client TLS, upload.auth.token)
            └── sample.txt                (업로드 테스트용 더미 파일)
```

두 프로젝트는 각자 `gradlew`/`build.gradle.kts`를 가진 완전히 독립된 Gradle 프로젝트이며, `src/main/proto/file_upload.proto`만 서로 동일한 내용을 유지해야 한다.

## 기능 목록

- `.proto` 기반 서비스 계약 정의 (`FileUploadService`)
- Client Streaming — `UploadFile`: 클라이언트가 파일을 청크(`bytes`)로 스트리밍 전송하면 서버가 전체 수신 후 디스크에 저장하고 결과(`success`, `message`, `size`)를 응답
- Server Streaming — `DownloadFile`: 클라이언트 요청 1개에 서버가 저장된 파일을 청크 스트림으로 응답
- Unary — `ListFiles`: 요청/응답이 각각 1개씩인 기본 RPC. `uploadDir`에 저장된 파일들의 이름·크기 목록을 반환
- `oneof`로 업로드 스트림 내 메타데이터(`FileInfo`)와 데이터(`chunk`)를 구분
- 서버/클라이언트를 별도 프로세스·별도 포트로 기동해 실제 네트워크(HTTP/2) 통신 확인
- 에러 케이스 처리 — 빈 파일명(`INVALID_ARGUMENT`), 순서 위반(`FAILED_PRECONDITION`), 크기 초과(`RESOURCE_EXHAUSTED`), 존재하지 않는 파일(`NOT_FOUND`), 커넥션 끊김 시 부분 파일 정리, 경로 탈출 방지
- 클라이언트 `FileUploadClient`가 stub 호출을 감싸 gRPC 상태 코드별로 실패 사유를 출력하고, 예외 대신 `null`을 반환해 호출부가 반복적인 try/catch 없이 실패를 처리

### 운영 관점 보강

- **TLS** — 서버가 자체 서명 인증서로 HTTP/2 위에 TLS를 적용(`grpc.server.security`), 클라이언트는 서버 인증서를 신뢰 앵커로 지정하고 `negotiation-type: TLS`로 접속. 인증서는 `scripts/gen-certs.sh`로 생성하며 개인키는 커밋하지 않는다.
- **인터셉터(로깅/인증)** — 전역 인터셉터로 관심사를 서비스 코드 밖으로 분리
  - 로깅: 서버/클라이언트 양쪽에서 모든 RPC의 시작·종료를 메서드명·상태 코드·소요 시간과 함께 한 줄로 기록(비 OK는 WARN)
  - 인증: 클라이언트가 `authorization: Bearer <token>`를 모든 호출에 부착, 서버가 검증해 불일치 시 `UNAUTHENTICATED`. 헬스체크·리플렉션은 검사에서 제외
  - `@Order`로 로깅(바깥) → 인증(안쪽) 순서를 고정해 인증 거부도 로그에 남게 함
- **헬스체크** — `grpc.health.v1.Health` 표준 서비스 자동 등록. 전체 상태(`""`)와 서비스 단위 상태(`upload.FileUploadService`)를 `SERVING`으로 표시하고, 클라이언트가 업로드 전에 `Check`로 확인

## 실행 방법

먼저 TLS 인증서를 한 번 생성한다(이후에는 생략).

```bash
./scripts/gen-certs.sh   # openssl 필요. Windows는 Git Bash에서 실행
```

터미널 두 개를 열어 각각 실행한다.

```bash
# 터미널 1 — 서버 (포트 9090, TLS)
cd upload-server
./gradlew.bat bootRun

# 터미널 2 — 클라이언트 (서버가 뜬 후 실행)
cd upload-client
./gradlew.bat bootRun
```

클라이언트 로그에 아래와 같이 출력되면 성공이다.

```
>>> gRPC health response: upload.FileUploadService=SERVING
>>> gRPC upload response: success=true, message=Uploaded sample.txt, size=255
>>> gRPC list response: sample.txt(255B)
>>> gRPC download response: downloaded 255 bytes, matchesOriginal=true
```

서버 로그에는 인터셉터가 각 RPC를 기록한다.

```
c.s.u.server.LoggingServerInterceptor : --> upload.FileUploadService/UploadFile
c.s.u.server.LoggingServerInterceptor : <-- upload.FileUploadService/UploadFile OK (303.3 ms)
```

서버 쪽에는 `upload-server/uploads/sample.txt`가 실제로 생성된다.

## 코딩 컨벤션

- **패키지 구조**: `com.saebak.upload` 하위에 `server`, `client` 서브패키지로 역할을 분리한다. 도메인이 늘어나면 `com.saebak.<domain>` 단위로 프로젝트/패키지를 새로 추가한다.
- **proto 파일**: 파일명은 서비스 도메인을 그대로 사용(`file_upload.proto`). `option java_multiple_files = true`로 메시지별 파일을 분리 생성하고, `java_package`는 Kotlin 패키지와 동일하게 맞춘다.
- **stub/서비스 명명**: `.proto`의 `service` 이름은 `XxxService`, 서버 구현체는 `XxxServiceImpl`로 통일한다.
- **코루틴 우선**: 블로킹/Async stub 대신 `grpc-kotlin-stub`의 코루틴 stub(`suspend fun`, `Flow`)을 기본으로 사용한다.
- **서버 설정**: `@GrpcService`가 붙은 구현체 하나당 파일 하나. 포트 등 설정은 코드에 하드코딩하지 않고 `application.yml`의 `grpc.*` 프로퍼티로 관리한다.
- **클라이언트 프로젝트에서 내장 gRPC 서버 비활성화**: `net.devh:grpc-spring-boot-starter`는 서버 기능을 기본 포함하므로, 순수 클라이언트 프로젝트에서는 `application.yml`에 `grpc.server.port: -1`을 명시해 포트 충돌을 막는다.
- **버전 고정**: `grpc-spring-boot-starter`가 기대하는 grpc 버전과 직접 명시하는 grpc 계열 의존성(`grpc-protobuf`, `grpc-kotlin-stub` 등) 버전을 반드시 맞춘다. 어긋나면 `ClassNotFoundException` 등 런타임 오류로 나타난다.
- **커밋 단위**: 도메인/구조 변경(예: 프로젝트 분리, 도메인 교체)과 기능 구현은 가능하면 커밋을 분리한다.
- **횡단 관심사는 인터셉터로**: 로깅·인증처럼 모든 RPC에 공통으로 걸리는 처리는 서비스 구현체에 넣지 않고 `@GrpcGlobalServerInterceptor`/`@GrpcGlobalClientInterceptor` 빈으로 분리하고, `@Order`로 적용 순서를 명시한다.
- **비밀값은 커밋 금지**: TLS 개인키 등은 `.gitignore`에 넣고 생성 스크립트(`scripts/gen-certs.sh`)로 재현한다. 토큰 같은 설정값은 `application.yml` 프로퍼티로 두되 운영에서는 환경변수/시크릿 매니저로 주입한다.

## 문서

- [`docs/test-report.md`](docs/test-report.md) — 최근 테스트 실행 결과(성능 수치 포함)와 재현 방법
- [`docs/test-cases.md`](docs/test-cases.md) — 서버/클라이언트 전체 테스트 케이스 명세(사전 조건·기대 결과)
- [`docs/refactoring-notes.md`](docs/refactoring-notes.md) — `FileUploadServiceImpl` 리팩토링 기록

## 다음 단계 (미구현)

- Bidirectional Streaming 예제 추가 — 4가지 gRPC 패턴 중 유일하게 빠져 있음
- 고정 토큰 대신 JWT 검증/mTLS(클라이언트 인증서) 등 실제 인증 방식으로 교체
- Spring Boot Actuator `health` 그룹과 gRPC 헬스 상태 연동
