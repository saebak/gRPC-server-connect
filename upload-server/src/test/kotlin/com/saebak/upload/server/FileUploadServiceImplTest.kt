package com.saebak.upload.server

import com.google.protobuf.ByteString
import com.saebak.upload.FileInfo
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.UploadRequest
import com.saebak.upload.UploadResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
        val serverName = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(FileUploadServiceImpl(tempDir.absolutePath))
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()
        stub = FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(channel)
    }

    @AfterEach
    fun tearDown() {
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
    fun `FileInfo 없이 chunk만 보내면 실패 응답을 반환한다`() = runBlocking {
        val response = stub.uploadFile(
            flow {
                emit(UploadRequest.newBuilder().setChunk(ByteString.copyFromUtf8("orphan chunk")).build())
            }
        )

        assertTrue(!response.success)
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
