# 리팩토링 노트 — FileUploadServiceImpl / 테스트

에러 케이스 처리를 추가한 뒤 코드를 다시 훑어보며 발견한 개선 포인트 3가지를 반영했다. 기능 변경은 없고, 모두 동작을 유지한 채 구조만 정리했다 (10개 테스트 전부 통과 확인).

## 1. 테스트의 in-process 서버 셋업 중복 제거

**문제**: `setUp()`에서 `InProcessServerBuilder`/`Channel`/`Stub`을 만드는 코드가, "허용 크기를 초과하면..." 테스트에서 커스텀 `maxUploadBytes`로 별도 서버를 띄우기 위해 그대로 복붙되어 있었다.

**변경**: `startInProcess(service)` / `stopInProcess(server, channel)` 헬퍼로 추출. `setUp()`과 커스텀 설정이 필요한 테스트가 동일한 헬퍼를 공유한다.

```kotlin
// Before — 테스트 안에서 통째로 재작성
val serverName = InProcessServerBuilder.generateName()
val limitedServer = InProcessServerBuilder.forName(serverName).directExecutor()
    .addService(FileUploadServiceImpl(tempDir.absolutePath, maxUploadBytes = 10))
    .build().start()
val limitedChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
val limitedStub = FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(limitedChannel)

// After — 헬퍼 재사용
val (limitedServer, limitedChannel, limitedStub) =
    startInProcess(FileUploadServiceImpl(tempDir.absolutePath, maxUploadBytes = 10))
```

앞으로 커스텀 설정으로 서버를 띄우는 테스트가 늘어도 이 헬퍼 하나로 처리된다.

## 2. 기본값 리터럴 정리

**문제**: `uploadDir`의 기본값 `"uploads"`와 `maxUploadBytes`의 기본값 `50MB`가 각각 두 곳에 따로 적혀 있었다.

```kotlin
@Value("${upload.dir:uploads}") uploadDirPath: String = "uploads",
@Value("${upload.max-bytes:52428800}") private val maxUploadBytes: Long = 50L * 1024 * 1024,
```

`@Value`의 SpEL 기본값(프로퍼티가 없을 때 Spring이 적용)과 Kotlin 파라미터 기본값(테스트처럼 생성자를 직접 호출할 때 적용)은 서로 다른 코드 경로라 완전히 하나로 합칠 수는 없지만, 최소한 Kotlin 쪽 리터럴은 이름 있는 상수로 옮겨서 실수로 두 값이 어긋나는 걸 알아채기 쉽게 했다.

```kotlin
companion object {
    const val DEFAULT_UPLOAD_DIR = "uploads"
    // application.yml의 upload.max-bytes 기본값(52428800)과 반드시 같은 값을 가리켜야 한다.
    const val DEFAULT_MAX_UPLOAD_BYTES = 50L * 1024 * 1024
}
```

## 3. `uploadFile`의 상태를 `UploadSession`으로 캡슐화

**문제**: `filename`, `totalSize`, `output`, `targetFile` 네 개의 지역 `var`가 서로 강하게 얽혀 있었다 — "파일 정보가 와야 output이 생기고, output이 있어야 청크를 쓸 수 있다"는 규칙이 코드에 암묵적으로만 존재했다.

```kotlin
// Before
var filename: String? = null
var totalSize = 0L
var output: FileOutputStream? = null
var targetFile: File? = null
```

**변경**: 업로드 스트림 하나의 진행 상태를 `UploadSession` 클래스로 묶었다. "파일과 열린 출력 스트림, 누적 크기"가 항상 함께 다닌다는 불변식이 타입으로 드러난다.

```kotlin
private class UploadSession(val file: File) {
    private val output = FileOutputStream(file)
    var size: Long = 0
        private set

    fun write(bytes: ByteArray) {
        output.write(bytes)
        size += bytes.size
    }

    fun close() = output.close()
}
```

`uploadFile`은 이제 `filename`(응답 메시지용)과 `session`(현재 진행 중인 업로드) 두 변수만 관리하면 된다. 크기 초과 체크도 `currentSession.size + bytes.size > maxUploadBytes`로, "지금까지 쓴 양 + 이번 청크"라는 의도가 그대로 드러난다.

## 적용하지 않은 것

리뷰 중 언급됐지만 지금 시점에는 실익이 적어 보류한 것들:

- **클라이언트 `chunkSize` → `uploadChunkSize` 이름 변경**: 서버의 `downloadChunkSize`와 나란히 보면 헷갈리지만, 두 값이 다른 프로젝트(다른 파일)에 있어 실제 혼동 위험은 낮다.
- **청크 읽기 반복문 공통 함수 추출**: `InputStream`을 청크로 읽어 `emit`/`write`하는 루프가 서버(`downloadFile`)와 클라이언트(업로드 flow)에 비슷하게 있지만, 각자 1곳씩만 사용 중이라 추출 이득이 크지 않다. Unary나 Bidirectional 예제가 추가돼 세 번째 사용처가 생기면 그때 뽑는 게 맞다.

## 검증

- `upload-server`: `./gradlew clean test --rerun` → **10/10 테스트 통과**
- `upload-client`: `./gradlew build` → 정상 (서버 쪽 내부 리팩토링이라 클라이언트 코드/계약에는 영향 없음)
