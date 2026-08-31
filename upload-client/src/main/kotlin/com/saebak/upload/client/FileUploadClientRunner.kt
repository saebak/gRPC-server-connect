package com.saebak.upload.client

import com.saebak.upload.FileUploadServiceGrpcKt
import io.grpc.StatusRuntimeException
import io.grpc.health.v1.HealthCheckRequest
import io.grpc.health.v1.HealthGrpc
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class FileUploadClientRunner(
    @GrpcClient("upload-service") stub: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub,
    @GrpcClient("upload-service") private val healthStub: HealthGrpc.HealthBlockingStub,
) : CommandLineRunner {

    private val client = FileUploadClient(stub)

    override fun run(vararg args: String?): Unit = runBlocking {
        if (!checkHealth()) return@runBlocking

        val resource = ClassPathResource("sample.txt")
        val filename = resource.filename ?: "sample.txt"

        val response = client.upload(filename, resource.inputStream) ?: return@runBlocking
        println(">>> gRPC upload response: success=${response.success}, message=${response.message}, size=${response.size}")

        val files = client.listFiles() ?: return@runBlocking
        println(">>> gRPC list response: ${files.joinToString { "${it.filename}(${it.size}B)" }}")

        val downloaded = client.download(filename) ?: return@runBlocking
        val original = resource.inputStream.use { it.readBytes() }
        val matches = downloaded.contentEquals(original)
        println(">>> gRPC download response: downloaded ${downloaded.size} bytes, matchesOriginal=$matches")
    }

    /** TLS 핸드셰이크 + 서버 가용성을 업로드 전에 한 번 확인한다(인증 토큰 없이 호출되는 공개 서비스). */
    internal fun checkHealth(): Boolean =
        try {
            val status = healthStub
                .check(HealthCheckRequest.newBuilder().setService("upload.FileUploadService").build())
                .status
            println(">>> gRPC health response: upload.FileUploadService=$status")
            status == io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING
        } catch (e: StatusRuntimeException) {
            println(">>> gRPC health check failed: ${e.status.code} - ${e.status.description}")
            false
        }
}
