package com.saebak.upload.client

import com.saebak.upload.FileUploadServiceGrpcKt
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
class FileUploadClientRunner(
    @GrpcClient("upload-service") stub: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub,
) : CommandLineRunner {

    private val client = FileUploadClient(stub)

    override fun run(vararg args: String?): Unit = runBlocking {
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
}
