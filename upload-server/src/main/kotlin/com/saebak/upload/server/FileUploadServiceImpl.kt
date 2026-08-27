package com.saebak.upload.server

import com.google.protobuf.ByteString
import com.saebak.upload.DownloadRequest
import com.saebak.upload.FileChunk
import com.saebak.upload.FileEntry
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.ListFilesRequest
import com.saebak.upload.ListFilesResponse
import com.saebak.upload.UploadRequest
import com.saebak.upload.UploadResponse
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.devh.boot.grpc.server.service.GrpcService
import org.springframework.beans.factory.annotation.Value
import java.io.File
import java.io.FileOutputStream

@GrpcService
class FileUploadServiceImpl(
    @Value("\${upload.dir:uploads}") uploadDirPath: String = DEFAULT_UPLOAD_DIR,
    @Value("\${upload.max-bytes:52428800}") private val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,
) : FileUploadServiceGrpcKt.FileUploadServiceCoroutineImplBase() {

    companion object {
        const val DEFAULT_UPLOAD_DIR = "uploads"

        // application.yml의 upload.max-bytes 기본값(52428800)과 반드시 같은 값을 가리켜야 한다.
        // @Value의 SpEL 기본값은 프로퍼티 부재 시, 이 Kotlin 기본값은 테스트 등에서 생성자를
        // 직접 호출할 때 각각 적용되는 서로 다른 경로라 리터럴을 하나로 합칠 수 없다.
        const val DEFAULT_MAX_UPLOAD_BYTES = 50L * 1024 * 1024
    }

    private val uploadDir = File(uploadDirPath).apply { mkdirs() }
    private val downloadChunkSize = 64 * 1024

    /** 업로드 스트림 하나의 진행 상태(대상 파일, 열린 출력 스트림, 누적 크기)를 한데 묶는다. */
    private class UploadSession(val file: File) {
        private val output = FileOutputStream(file)
        var size: Long = 0
            private set

        fun write(bytes: ByteArray) {
            output.write(bytes)
            size += bytes.size
        }

        fun close() = output.close()
    }

    override suspend fun uploadFile(requests: Flow<UploadRequest>): UploadResponse {
        var filename: String? = null
        var session: UploadSession? = null

        try {
            requests.collect { request ->
                when (request.payloadCase) {
                    UploadRequest.PayloadCase.INFO -> {
                        val name = request.info.filename
                        val file = resolveWithinUploadDir(name)
                            ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("invalid filename: $name"))
                        filename = name
                        session = UploadSession(file)
                    }
                    UploadRequest.PayloadCase.CHUNK -> {
                        val currentSession = session
                            ?: throw StatusException(Status.FAILED_PRECONDITION.withDescription("chunk received before file info"))
                        val bytes = request.chunk.toByteArray()
                        if (currentSession.size + bytes.size > maxUploadBytes) {
                            throw StatusException(
                                Status.RESOURCE_EXHAUSTED.withDescription(
                                    "file exceeds max allowed size of $maxUploadBytes bytes"
                                )
                            )
                        }
                        currentSession.write(bytes)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // 커넥션이 끊기거나(취소) 검증에 실패한 경우, 반쪽짜리 파일을 디스크에 남기지 않는다
            session?.close()
            session?.file?.delete()
            throw e
        }

        session?.close()

        return UploadResponse.newBuilder()
            .setSuccess(filename != null)
            .setMessage(if (filename != null) "Uploaded $filename" else "No file info received")
            .setSize(session?.size ?: 0)
            .build()
    }

    override fun downloadFile(request: DownloadRequest): Flow<FileChunk> = flow {
        if (request.filename.isBlank()) {
            throw StatusException(Status.INVALID_ARGUMENT.withDescription("filename must not be blank"))
        }
        val file = resolveWithinUploadDir(request.filename)
        if (file == null || !file.isFile) {
            throw StatusException(Status.NOT_FOUND.withDescription("File not found: ${request.filename}"))
        }

        file.inputStream().use { input ->
            val buffer = ByteArray(downloadChunkSize)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                emit(FileChunk.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, bytesRead)).build())
            }
        }
    }

    override suspend fun listFiles(request: ListFilesRequest): ListFilesResponse {
        val files = uploadDir.listFiles { file -> file.isFile }
            ?.sortedBy { it.name }
            ?.map { file -> FileEntry.newBuilder().setFilename(file.name).setSize(file.length()).build() }
            ?: emptyList()

        return ListFilesResponse.newBuilder().addAllFiles(files).build()
    }

    /**
     * 클라이언트가 보낸 파일명이 uploadDir 밖을 가리키지 않는지(경로 탈출) 확인하고,
     * 안전하면 그 안의 File을, 아니면 null을 반환한다.
     */
    private fun resolveWithinUploadDir(filename: String): File? {
        if (filename.isBlank()) return null
        val file = File(uploadDir, filename)
        return if (file.canonicalFile.parentFile == uploadDir.canonicalFile) file else null
    }
}
