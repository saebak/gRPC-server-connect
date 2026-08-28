package com.saebak.upload.client

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order

/**
 * 클라이언트가 보낸 RPC의 종료 상태와 소요 시간을 한 줄로 남긴다.
 * 전역 클라이언트 인터셉터 중 가장 바깥(@Order 최소값)에 둔다.
 */
@Order(10)
@GrpcGlobalClientInterceptor
class LoggingClientInterceptor : ClientInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> {
        val name = method.fullMethodName
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                val startNanos = System.nanoTime()
                val observed = object :
                    ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                    override fun onClose(status: Status, trailers: Metadata) {
                        val elapsedMs = "%.1f".format((System.nanoTime() - startNanos) / 1_000_000.0)
                        if (status.isOk) {
                            log.info("{} {} ({} ms)", name, status.code, elapsedMs)
                        } else {
                            log.warn("{} {} ({} ms): {}", name, status.code, elapsedMs, status.description)
                        }
                        super.onClose(status, trailers)
                    }
                }
                super.start(observed, headers)
            }
        }
    }
}
