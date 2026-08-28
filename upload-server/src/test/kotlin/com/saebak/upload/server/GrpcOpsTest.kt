package com.saebak.upload.server

import com.saebak.upload.FileInfo
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.UploadRequest
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.health.v1.HealthGrpc
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.services.HealthStatusManager
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 운영용 인터셉터(인증)와 grpc.health.v1.Health 서비스를 in-process gRPC 경로에서 검증한다.
 * TLS는 소켓이 필요하므로 별도 TlsIntegrationTest에서 확인한다.
 */
class GrpcOpsTest {

    private val token = "dev-secret-token"

    @TempDir
    lateinit var tempDir: File

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    @BeforeEach
    fun setUp() {
        val name = InProcessServerBuilder.generateName()
        val health = HealthStatusManager().apply {
            setStatus("upload.FileUploadService", ServingStatus.SERVING)
        }
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    FileUploadServiceImpl(tempDir.absolutePath),
                    AuthServerInterceptor(token),
                )
            )
            .addService(ServerInterceptors.intercept(health.healthService, AuthServerInterceptor(token)))
            .build()
            .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
        channel.awaitTermination(5, TimeUnit.SECONDS)
        server.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun fileUploadStub() = FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(channel)

    private fun authedFileUploadStub(bearer: String): FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub {
        val md = Metadata().apply {
            put(AuthServerInterceptor.AUTHORIZATION_KEY, "Bearer $bearer")
        }
        return fileUploadStub().withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md))
    }

    private fun fileInfoRequest(name: String) = flow {
        emit(UploadRequest.newBuilder().setInfo(FileInfo.newBuilder().setFilename(name).build()).build())
    }

    @Test
    fun `유효한 토큰이면 RPC가 통과한다`() = runBlocking {
        val response = authedFileUploadStub(token).uploadFile(fileInfoRequest("ok.txt"))

        assertEquals(true, response.success)
    }

    @Test
    fun `토큰이 없으면 UNAUTHENTICATED를 반환한다`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking { fileUploadStub().uploadFile(fileInfoRequest("nope.txt")) }
        }

        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(exception).code)
    }

    @Test
    fun `토큰이 틀리면 UNAUTHENTICATED를 반환한다`() {
        val exception = assertThrows(Exception::class.java) {
            runBlocking { authedFileUploadStub("wrong-token").uploadFile(fileInfoRequest("nope.txt")) }
        }

        assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(exception).code)
    }

    @Test
    fun `헬스체크는 토큰 없이도 SERVING을 반환한다`() {
        val response = HealthGrpc.newBlockingStub(channel)
            .check(HealthCheckRequest.newBuilder().setService("upload.FileUploadService").build())

        assertEquals(ServingStatus.SERVING, response.status)
    }
}
