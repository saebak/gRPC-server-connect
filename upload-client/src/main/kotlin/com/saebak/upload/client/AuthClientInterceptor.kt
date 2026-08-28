package com.saebak.upload.client

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order

/**
 * 모든 아웃바운드 RPC에 `authorization: Bearer <token>` 메타데이터를 붙인다.
 * 서버의 AuthServerInterceptor와 짝을 이룬다. 토큰은 `upload.auth.token` 프로퍼티로 관리한다.
 */
@Order(20)
@GrpcGlobalClientInterceptor
class AuthClientInterceptor(
    @Value("\${upload.auth.token:dev-secret-token}") private val token: String,
) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                headers.put(AUTHORIZATION_KEY, "Bearer $token")
                super.start(responseListener, headers)
            }
        }

    companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}
