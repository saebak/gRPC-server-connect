package com.saebak.upload.server

import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order

/**
 * 모든 RPC의 시작/종료를 한 줄씩 로그로 남긴다. 종료 시 상태 코드와 소요 시간을 함께 기록해,
 * 실패(비 OK)면 WARN으로 올린다. 전역 인터셉터 중 가장 바깥(@Order 최소값)에 두어
 * 인증 거부까지 포함한 모든 종료를 관측한다.
 */
@Order(10)
@GrpcGlobalServerInterceptor
class LoggingServerInterceptor : ServerInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        val startNanos = System.nanoTime()
        log.info("--> {}", method)

        val observed = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                val elapsedMs = "%.1f".format((System.nanoTime() - startNanos) / 1_000_000.0)
                if (status.isOk) {
                    log.info("<-- {} {} ({} ms)", method, status.code, elapsedMs)
                } else {
                    log.warn("<-- {} {} ({} ms): {}", method, status.code, elapsedMs, status.description)
                }
                super.close(status, trailers)
            }
        }
        return next.startCall(observed, headers)
    }
}
