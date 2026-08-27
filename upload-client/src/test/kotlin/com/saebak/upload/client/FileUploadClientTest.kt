package com.saebak.upload.client

import com.saebak.upload.FileEntry
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.UploadResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * FileUploadClient가 서버 응답/에러를 어떻게 다루는지, 실제 stub -> in-process 서버
 * 경로(직렬화 포함)를 태워 검증한다. 서버 쪽 동작은 FakeFileUploadService로 대체한다.
 */
class FileUploadClientTest {

    private val fakeService = FakeFileUploadService()

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var client: FileUploadClient

    @BeforeEach
    fun setUp() {
        val serverName = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(serverName).directExecutor().addService(fakeService).build().start()
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        client = FileUploadClient(FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(channel))
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
        channel.awaitTermination(5, TimeUnit.SECONDS)
        server.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `업로드가 성공하면 서버 응답을 그대로 반환한다`() = runBlocking {
        fakeService.uploadResponse = UploadResponse.newBuilder().setSuccess(true).setMessage("ok").setSize(3).build()

        val response = client.upload("a.txt", ByteArrayInputStream("abc".toByteArray()))

        assertEquals(true, response?.success)
        assertEquals(3L, response?.size)
    }

    @Test
    fun `업로드가 RESOURCE_EXHAUSTED로 실패하면 예외 대신 null을 반환한다`() = runBlocking {
        fakeService.uploadError = Status.RESOURCE_EXHAUSTED.withDescription("too big")

        val response = client.upload("a.txt", ByteArrayInputStream("abc".toByteArray()))

        assertNull(response)
    }

    @Test
    fun `목록 조회가 성공하면 파일 목록을 반환한다`() = runBlocking {
        fakeService.listResponse = com.saebak.upload.ListFilesResponse.newBuilder()
            .addFiles(FileEntry.newBuilder().setFilename("a.txt").setSize(3).build())
            .build()

        val files = client.listFiles()

        assertEquals(listOf("a.txt"), files?.map { it.filename })
    }

    @Test
    fun `목록 조회가 실패하면 예외 대신 null을 반환한다`() = runBlocking {
        fakeService.listError = Status.UNAVAILABLE.withDescription("down")

        val files = client.listFiles()

        assertNull(files)
    }

    @Test
    fun `다운로드가 성공하면 청크를 이어붙인 바이트를 반환한다`() = runBlocking {
        fakeService.downloadChunks = listOf("hel".toByteArray(), "lo".toByteArray())

        val downloaded = client.download("a.txt")

        assertEquals("hello", downloaded?.toString(Charsets.UTF_8))
    }

    @Test
    fun `다운로드가 NOT_FOUND로 실패하면 예외 대신 null을 반환한다`() = runBlocking {
        fakeService.downloadError = Status.NOT_FOUND.withDescription("nope")

        val downloaded = client.download("missing.txt")

        assertNull(downloaded)
    }
}
