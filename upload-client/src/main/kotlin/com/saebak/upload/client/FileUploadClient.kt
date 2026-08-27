package com.saebak.upload.client

import com.google.protobuf.ByteString
import com.saebak.upload.DownloadRequest
import com.saebak.upload.FileEntry
import com.saebak.upload.FileInfo
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.ListFilesRequest
import com.saebak.upload.UploadRequest
import com.saebak.upload.UploadResponse
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * gRPC stub 호출을 감싸, 서버가 던지는 상태 코드별로 사람이 읽을 수 있는 실패 사유를 붙여준다.
 * 실패 시 예외를 전파하는 대신 null을 반환해, 호출부(CommandLineRunner 등)가 매번 try/catch를
 * 반복하지 않고도 "이 호출이 실패했다"는 사실만으로 다음 동작을 결정할 수 있게 한다.
 */
class FileUploadClient(
    private val stub: FileUploadServiceGrpcKt.FileUploadServiceCoroutineStub,
) {

    private val uploadChunkSize = 1024

    suspend fun upload(filename: String, content: InputStream): UploadResponse? {
        val requests = flow {
            emit(UploadRequest.newBuilder().setInfo(FileInfo.newBuilder().setFilename(filename).build()).build())
            content.use { input ->
                val buffer = ByteArray(uploadChunkSize)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    emit(UploadRequest.newBuilder().setChunk(ByteString.copyFrom(buffer, 0, bytesRead)).build())
                }
            }
        }

        return runCatchingStatus("upload", filename) { stub.uploadFile(requests) }
    }

    suspend fun listFiles(): List<FileEntry>? =
        runCatchingStatus("list", null) { stub.listFiles(ListFilesRequest.newBuilder().build()).filesList }

    suspend fun download(filename: String): ByteArray? = runCatchingStatus("download", filename) {
        val output = ByteArrayOutputStream()
        stub.downloadFile(DownloadRequest.newBuilder().setFilename(filename).build())
            .collect { chunk -> output.write(chunk.chunk.toByteArray()) }
        output.toByteArray()
    }

    private suspend fun <T> runCatchingStatus(operation: String, filename: String?, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: StatusException) {
            reportError(operation, filename, e)
            null
        } catch (e: StatusRuntimeException) {
            reportError(operation, filename, e)
            null
        }

    private fun reportError(operation: String, filename: String?, e: Throwable) {
        val status = Status.fromThrowable(e)
        val hint = when (status.code) {
            Status.Code.NOT_FOUND -> "파일을 찾을 수 없습니다"
            Status.Code.INVALID_ARGUMENT -> "요청이 올바르지 않습니다"
            Status.Code.RESOURCE_EXHAUSTED -> "허용된 크기를 초과했습니다"
            Status.Code.FAILED_PRECONDITION -> "요청 순서가 올바르지 않습니다"
            Status.Code.UNAVAILABLE -> "서버에 연결할 수 없습니다"
            else -> "알 수 없는 오류가 발생했습니다"
        }
        val target = filename?.let { " ($it)" } ?: ""
        println(">>> gRPC $operation$target failed: ${status.code} - $hint (${status.description})")
    }
}
