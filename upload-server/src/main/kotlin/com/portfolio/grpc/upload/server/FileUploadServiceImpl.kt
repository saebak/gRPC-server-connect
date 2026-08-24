package com.portfolio.grpc.upload.server

import com.portfolio.grpc.upload.FileUploadServiceGrpcKt
import com.portfolio.grpc.upload.UploadRequest
import com.portfolio.grpc.upload.UploadResponse
import kotlinx.coroutines.flow.Flow
import net.devh.boot.grpc.server.service.GrpcService
import org.springframework.beans.factory.annotation.Value
import java.io.File
import java.io.FileOutputStream

@GrpcService
class FileUploadServiceImpl(
    @Value("\${upload.dir:uploads}") uploadDirPath: String = "uploads",
) : FileUploadServiceGrpcKt.FileUploadServiceCoroutineImplBase() {

    private val uploadDir = File(uploadDirPath).apply { mkdirs() }

    override suspend fun uploadFile(requests: Flow<UploadRequest>): UploadResponse {
        var filename: String? = null
        var totalSize = 0L
        var output: FileOutputStream? = null

        try {
            requests.collect { request ->
                when (request.payloadCase) {
                    UploadRequest.PayloadCase.INFO -> {
                        filename = request.info.filename
                        output = FileOutputStream(File(uploadDir, filename!!))
                    }
                    UploadRequest.PayloadCase.CHUNK -> {
                        val bytes = request.chunk.toByteArray()
                        output?.write(bytes)
                        totalSize += bytes.size
                    }
                    else -> Unit
                }
            }
        } finally {
            output?.close()
        }

        return UploadResponse.newBuilder()
            .setSuccess(filename != null)
            .setMessage(if (filename != null) "Uploaded $filename" else "No file info received")
            .setSize(totalSize)
            .build()
    }
}
