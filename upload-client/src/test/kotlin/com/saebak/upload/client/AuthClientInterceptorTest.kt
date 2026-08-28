package com.saebak.upload.client

import com.saebak.upload.FileUploadServiceGrpcKt
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * AuthClientInterceptor가 모든 아웃바운드 RPC에 `authorization: Bearer <token>` 메타데이터를
 * 붙이는지, 서버 쪽에서 헤더를 가로채 검증한다.
 */
class AuthClientInterceptorTest {

    private val captured = mutableListOf<String?>()
    private val fakeService = FakeFileUploadService()

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel

    private val authorizationKey: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    @BeforeEach
    fun setUp() {
        val headerCaptor = object : ServerInterceptor {
            override fun <ReqT, RespT> interceptCall(
                call: ServerCall<ReqT, RespT>,
                headers: Metadata,
                next: ServerCallHandler<ReqT, RespT>,
            ): ServerCall.Listener<ReqT> {
                captured.add(headers.get(authorizationKey))
                return next.startCall(call, headers)
            }
        }

        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(ServerInterceptors.intercept(fakeService, headerCaptor))
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

    @Test
    fun `인터셉터가 Bearer 토큰을 붙인다`() = runBlocking {
        val intercepted = ClientInterceptors.intercept(channel, AuthClientInterceptor("dev-secret-token"))
        val client = FileUploadClient(FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub(intercepted))

        client.listFiles()

        assertEquals(listOf("Bearer dev-secret-token"), captured)
    }
}
