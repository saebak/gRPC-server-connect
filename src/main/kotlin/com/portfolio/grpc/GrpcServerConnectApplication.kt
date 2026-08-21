package com.portfolio.grpc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GrpcServerConnectApplication

fun main(args: Array<String>) {
    runApplication<GrpcServerConnectApplication>(*args)
}
