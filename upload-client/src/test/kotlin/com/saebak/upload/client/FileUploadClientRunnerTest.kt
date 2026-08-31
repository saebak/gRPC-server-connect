package com.saebak.upload.client

import com.saebak.upload.FileUploadServiceGrpcKt
import io.grpc.Status
import io.grpc.health.v1.HealthCheckResponse
import io.grpc.health.v1.HealthGrpc
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class FileUploadClientRunnerTest {

    private val uploadStub = mock(FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub::class.java)
    private val healthStub = mock(HealthGrpc.HealthBlockingStub::class.java)

    @Test
    fun `헬스 상태가 NOT_SERVING이면 본 작업을 시작하지 않는다`() {
        `when`(healthStub.check(any())).thenReturn(
            HealthCheckResponse.newBuilder()
                .setStatus(HealthCheckResponse.ServingStatus.NOT_SERVING)
                .build()
        )
        val runner = FileUploadClientRunner(uploadStub, healthStub)

        runner.run()

        assertFalse(runner.checkHealth())
        verifyNoInteractions(uploadStub)
    }

    @Test
    fun `헬스체크 호출이 실패하면 본 작업을 시작하지 않는다`() {
        `when`(healthStub.check(any())).thenThrow(Status.UNAVAILABLE.withDescription("down").asRuntimeException())
        val runner = FileUploadClientRunner(uploadStub, healthStub)

        runner.run()

        assertFalse(runner.checkHealth())
        verifyNoInteractions(uploadStub)
    }
}
