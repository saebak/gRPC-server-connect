package com.saebak.upload.client

import com.saebak.upload.DownloadRequest
import com.saebak.upload.FileChunk
import com.saebak.upload.FileUploadServiceGrpcKt
import com.saebak.upload.ListFilesRequest
import com.saebak.upload.ListFilesResponse
import com.saebak.upload.UploadRequest
import com.saebak.upload.UploadResponse
import io.grpc.Status
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * upload-client는 upload-server 모듈에 의존하지 않으므로(완전히 독립된 프로젝트),
 * FileUploadClient를 검증하기 위한 최소한의 서버 동작을 이 페이크로 흉내낸다.
 * 각 응답/에러는 필드에 미리 세팅해두고 in-process 서버로 띄워 사용한다.
 */
class FakeFileUploadService : FileUploadServiceGrpcKt.FileUploadServiceCoroutineImplBase() {

    var uploadResponse: UploadResponse = UploadResponse.getDefaultInstance()
    var uploadError: Status? = null

    var listResponse: ListFilesResponse = ListFilesResponse.getDefaultInstance()
    var listError: Status? = null

    var downloadChunks: List<ByteArray> = emptyList()
    var downloadError: Status? = null

    override suspend fun uploadFile(requests: Flow<UploadRequest>): UploadResponse {
        requests.collect { }
        uploadError?.let { throw it.asRuntimeException() }
        return uploadResponse
    }

    override suspend fun listFiles(request: ListFilesRequest): ListFilesResponse {
        listError?.let { throw it.asRuntimeException() }
        return listResponse
    }

    override fun downloadFile(request: DownloadRequest): Flow<FileChunk> = flow {
        downloadError?.let { throw it.asRuntimeException() }
        downloadChunks.forEach { bytes ->
            emit(FileChunk.newBuilder().setChunk(com.google.protobuf.ByteString.copyFrom(bytes)).build())
        }
    }
}
