package com.saebak.upload.server

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.annotation.Order

/**
 * `authorization: Bearer <token>` 메타데이터를 검사한다. 토큰이 없거나 일치하지 않으면
 * RPC를 UNAUTHENTICATED로 즉시 종료한다. 인프라 성격의 서비스(헬스체크·리플렉션)는
 * 크리덴셜 없이 조회할 수 있어야 하므로 검사에서 제외한다.
 *
 * 데모용 고정 토큰(`upload.auth.token`)이며, 실제 서비스에서는 JWT 검증/introspection 등으로 대체한다.
 */
@Order(20)
@GrpcGlobalServerInterceptor
class AuthServerInterceptor(
    @Value("\${upload.auth.token:dev-secret-token}") private val expectedToken: String,
) : ServerInterceptor {

    companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private const val BEARER_PREFIX = "Bearer "

        private val PUBLIC_SERVICES = setOf(
            "grpc.health.v1.Health",
            "grpc.reflection.v1alpha.ServerReflection",
        )
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (call.methodDescriptor.serviceName in PUBLIC_SERVICES) {
            return next.startCall(call, headers)
        }

        val authorization = headers.get(AUTHORIZATION_KEY)
        val token = authorization?.removePrefix(BEARER_PREFIX)?.trim()

        return if (token == expectedToken) {
            next.startCall(call, headers)
        } else {
            val reason = if (authorization == null) "missing authorization metadata" else "invalid token"
            call.close(Status.UNAUTHENTICATED.withDescription(reason), Metadata())
            object : ServerCall.Listener<ReqT>() {}
        }
    }
}
