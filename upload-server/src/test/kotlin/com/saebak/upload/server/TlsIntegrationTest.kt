package com.saebak.upload.server

import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.ListFilesRequest
import io.grpc.netty.GrpcSslContexts
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 실제 TCP 소켓 위에서 TLS 핸드셰이크가 성립하고 RPC가 통하는지 확인한다.
 * 인증서는 scripts/gen-certs.sh 산출물(upload-server 모듈의 certs 디렉터리)을 그대로 사용하며,
 * 없으면(예: openssl 미설치 CI) 테스트를 건너뛴다.
 */
class TlsIntegrationTest {

    private val certFile = File("certs/server.crt")
    private val keyFile = File("certs/server.key")

    @TempDir
    lateinit var tempDir: File

    private lateinit var server: io.grpc.Server
    private lateinit var channel: io.grpc.ManagedChannel

    @BeforeEach
    fun setUp() {
        assumeTrue(certFile.exists() && keyFile.exists(), "TLS 인증서 없음 — scripts/gen-certs.sh 실행 필요")

        server = NettyServerBuilder.forPort(0)
            .useTransportSecurity(certFile, keyFile)
            .addService(FileUploadServiceImpl(tempDir.absolutePath))
            .build()
            .start()

        channel = NettyChannelBuilder.forAddress("localhost", server.port)
            .sslContext(GrpcSslContexts.forClient().trustManager(certFile).build())
            .overrideAuthority("localhost")
            .build()
    }

    @AfterEach
    fun tearDown() {
        if (::channel.isInitialized) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        if (::server.isInitialized) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `TLS 채널로 RPC를 호출할 수 있다`() = runBlocking {
        val stub = FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(channel)

        val response = stub.listFiles(ListFilesRequest.newBuilder().build())

        assertTrue(response.filesList.isEmpty())
    }
}
