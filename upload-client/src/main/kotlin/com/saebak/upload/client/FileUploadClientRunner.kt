package com.saebak.upload.client

import com.google.protobuf.ByteString
import com.saebak.upload.DownloadRequest
import com.saebak.upload.FileInfo
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.ListFilesRequest
import com.saebak.upload.UploadRequest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@Component
class FileUploadClientRunner(
    @GrpcClient("upload-service") private val stub: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub,
) : CommandLineRunner {

    private val chunkSize = 1024

    override fun run(vararg args: String?): Unit = runBlocking {
        val resource = ClassPathResource("sample.txt")
        val filename = resource.filename ?: "sample.txt"

        val requests = flow {
            emit(
                UploadRequest.newBuilder()
                    .setInfo(FileInfo.newBuilder().setFilename(filename).build())
                    .build()
            )
            resource.inputStream.use { input ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    emit(
                        UploadRequest.newBuilder()
                            .setChunk(ByteString.copyFrom(buffer, 0, bytesRead))
                            .build()
                    )
                }
            }
        }

        val response = stub.uploadFile(requests)
        println(">>> gRPC upload response: success=${response.success}, message=${response.message}, size=${response.size}")

        val listResponse = stub.listFiles(ListFilesRequest.newBuilder().build())
        println(">>> gRPC list response: ${listResponse.filesList.joinToString { "${it.filename}(${it.size}B)" }}")

        val downloaded = ByteArrayOutputStream()
        stub.downloadFile(DownloadRequest.newBuilder().setFilename(filename).build())
            .collect { fileChunk -> downloaded.write(fileChunk.chunk.toByteArray()) }

        val original = resource.inputStream.use { it.readBytes() }
        val matches = downloaded.toByteArray().contentEquals(original)
        println(">>> gRPC download response: downloaded ${downloaded.size()} bytes, matchesOriginal=$matches")
    }
}
