package com.saebak.upload.server

import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import io.grpc.protobuf.services.HealthStatusManager
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * grpc-spring-boot-starter가 classpath의 `grpc-services`를 보고 자동 등록한
 * `grpc.health.v1.Health` 서비스에, 전체 상태("")와 함께 서비스 단위 상태를 채운다.
 *
 * 클라이언트는 `grpc.health.v1.Health/Check` 로
 *   - service="" (서버 전체)
 *   - service="upload.FileUploadService"
 * 를 조회할 수 있다.
 */
@Component
class GrpcHealthConfig(
    private val healthStatusManager: HealthStatusManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun markServing() {
        healthStatusManager.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING)
        healthStatusManager.setStatus(FILE_UPLOAD_SERVICE, ServingStatus.SERVING)
        log.info("gRPC health: '{}' 및 '' → SERVING", FILE_UPLOAD_SERVICE)
    }

    companion object {
        const val FILE_UPLOAD_SERVICE = "upload.FileUploadService"
    }
}
