package com.saebak.upload.server

import com.google.protobuf.ByteString
import com.saebak.upload.DownloadRequest
import com.saebak.upload.FileInfo
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.UploadRequest
import com.saebak.upload.UploadResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

/**
 * FileUploadServiceImpl을 in-process gRPC 서버에 등록해 실제 stub -> service 경로
 * (직렬화/스트림 처리 포함)를 그대로 태우되, 소켓 없이 빠르게 반복 실행한다.
 */
class FileUploadServiceImplTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub

    @BeforeEach
    fun setUp() {
        val (srv, ch, st) = startInProcess(FileUploadServiceImpl(tempDir.absolutePath))
        server = srv
        channel = ch
        stub = st
    }

    @AfterEach
    fun tearDown() = stopInProcess(server, channel)

    /** in-process gRPC 서버·채널·stub을 한 번에 띄운다. 커스텀 설정으로 별도 서버가 필요한 테스트에서도 재사용한다. */
    private fun startInProcess(
        service: FileUploadServiceImpl,
    ): Triple<Server, ManagedChannel, FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub> {
        val serverName = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start()
        val channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
        val stub = FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(channel)
        return Triple(server, channel, stub)
    }

    private fun stopInProcess(server: Server, channel: ManagedChannel) {
        channel.shutdownNow()
        server.shutdownNow()
        channel.awaitTermination(5, TimeUnit.SECONDS)
        server.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `업로드한 파일이 서버에 원본 그대로 저장된다`() = runBlocking {
        val content = "hello grpc client streaming".toByteArray()

        val response = upload("small.txt", content, chunkSize = 8)

        assertTrue(response.success)
        assertEquals(content.size.toLong(), response.size)
        assertEquals(content.toList(), File(tempDir, "small.txt").readBytes().toList())
    }

    @Test
    fun `FileInfo 없이 chunk만 보내면 FAILED_PRECONDITION을 반환한다`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                stub.uploadFile(
                    flow {
                        emit(UploadRequest.newBuilder().setChunk(ByteString.copyFromUtf8("orphan chunk")).build())
                    }
                )
            }
        }

        assertEquals(Status.Code.FAILED_PRECONDITION, Status.fromThrowable(exception).code)
    }

    @Test
    fun `빈 파일명으로 업로드하면 INVALID_ARGUMENT를 반환한다`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                stub.uploadFile(
                    flow {
                        emit(UploadRequest.newBuilder().setInfo(FileInfo.newBuilder().setFilename("").build()).build())
                    }
                )
            }
        }

        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(exception).code)
    }

    @Test
    fun `청크 없이 FileInfo만 보내면 크기 0인 빈 파일이 정상 업로드된다`() = runBlocking {
        val response = upload("empty.txt", ByteArray(0), chunkSize = 1)

        assertTrue(response.success)
        assertEquals(0L, response.size)
        assertTrue(File(tempDir, "empty.txt").isFile)
        assertEquals(0L, File(tempDir, "empty.txt").length())
    }

    @Test
    fun `업로드 중 커넥션이 끊기면 서버에 남은 부분 파일을 정리한다`() {
        // 실제 네트워크 취소가 서버까지 전파되는 데는 비동기 지연이 있으므로,
        // gRPC stub/channel을 거치지 않고 서비스의 정리 로직 자체를 직접 검증한다.
        val service = FileUploadServiceImpl(tempDir.absolutePath)

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                service.uploadFile(
                    flow {
                        emit(
                            UploadRequest.newBuilder()
                                .setInfo(FileInfo.newBuilder().setFilename("interrupted.bin").build())
                                .build()
                        )
                        emit(UploadRequest.newBuilder().setChunk(ByteString.copyFromUtf8("partial data")).build())
                        throw RuntimeException("simulated connection drop")
                    }
                )
            }
        }

        assertTrue(!File(tempDir, "interrupted.bin").exists())
    }

    @Test
    fun `허용 크기를 초과하면 RESOURCE_EXHAUSTED를 반환하고 부분 파일을 남기지 않는다`() {
        val (limitedServer, limitedChannel, limitedStub) =
            startInProcess(FileUploadServiceImpl(tempDir.absolutePath, maxUploadBytes = 10))

        try {
            val exception = assertThrows(Exception::class.java) {
                runBlocking {
                    limitedStub.uploadFile(
                        flow {
                            emit(
                                UploadRequest.newBuilder()
                                    .setInfo(FileInfo.newBuilder().setFilename("toobig.bin").build())
                                    .build()
                            )
                            emit(UploadRequest.newBuilder().setChunk(ByteString.copyFrom(ByteArray(1024))).build())
                        }
                    )
                }
            }

            assertEquals(Status.Code.RESOURCE_EXHAUSTED, Status.fromThrowable(exception).code)
            assertTrue(!File(tempDir, "toobig.bin").exists())
        } finally {
            stopInProcess(limitedServer, limitedChannel)
        }
    }

    @Test
    fun `대용량 파일을 다수의 청크로 스트리밍해도 허용 시간 내에 완료된다`() = runBlocking {
        val sizeBytes = 20 * 1024 * 1024 // 20MB
        val chunkSize = 64 * 1024
        val content = ByteArray(sizeBytes) { (it % 256).toByte() }
        val filename = "large-${UUID.randomUUID()}.bin"

        lateinit var response: UploadResponse
        val elapsedMs = measureTimeMillis {
            response = upload(filename, content, chunkSize)
        }

        assertTrue(response.success)
        assertEquals(sizeBytes.toLong(), response.size)
        assertEquals(sizeBytes.toLong(), File(tempDir, filename).length())

        val seconds = elapsedMs / 1000.0
        val throughputMBps = (sizeBytes / (1024.0 * 1024.0)) / seconds.coerceAtLeast(0.001)
        println(">>> uploaded ${sizeBytes / (1024 * 1024)}MB in ${elapsedMs}ms (%.2f MB/s)".format(throughputMBps))

        assertTrue(elapsedMs < 5_000, "업로드가 너무 오래 걸림: ${elapsedMs}ms")
    }

    @Test
    fun `업로드한 파일을 다운로드하면 원본과 동일한 바이트를 스트림으로 받는다`() = runBlocking {
        val content = "download roundtrip test".toByteArray()
        upload("roundtrip.txt", content, chunkSize = 5)

        val downloaded = ByteArrayOutputStream()
        stub.downloadFile(DownloadRequest.newBuilder().setFilename("roundtrip.txt").build())
            .collect { fileChunk -> downloaded.write(fileChunk.chunk.toByteArray()) }

        assertEquals(content.toList(), downloaded.toByteArray().toList())
    }

    @Test
    fun `존재하지 않는 파일을 다운로드하면 NOT_FOUND를 반환한다`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking {
                stub.downloadFile(DownloadRequest.newBuilder().setFilename("nope.txt").build()).toList()
            }
        }

        assertEquals(Status.Code.NOT_FOUND, Status.fromThrowable(exception).code)
    }

    @Test
    fun `빈 파일명으로 다운로드를 요청하면 INVALID_ARGUMENT를 반환한다`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking { stub.downloadFile(DownloadRequest.newBuilder().setFilename("").build()).toList() }
        }

        assertEquals(Status.Code.INVALID_ARGUMENT, Status.fromThrowable(exception).code)
    }

    private suspend fun upload(filename: String, content: ByteArray, chunkSize: Int): UploadResponse =
        stub.uploadFile(
            flow {
                emit(
                    UploadRequest.newBuilder()
                        .setInfo(FileInfo.newBuilder().setFilename(filename).build())
                        .build()
                )
                var offset = 0
                while (offset < content.size) {
                    val end = minOf(offset + chunkSize, content.size)
                    emit(
                        UploadRequest.newBuilder()
                            .setChunk(ByteString.copyFrom(content, offset, end - offset))
                            .build()
                    )
                    offset = end
                }
            }
        )
}
