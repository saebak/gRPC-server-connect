package com.portfolio.grpc.upload.client

import com.google.protobuf.ByteString
import com.portfolio.grpc.upload.FileInfo
import com.portfolio.grpc.upload.FileUploadServiceGrpcKt
import com.portfolio.grpc.upload.UploadRequest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.boot.CommandLineRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

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
    }
}
