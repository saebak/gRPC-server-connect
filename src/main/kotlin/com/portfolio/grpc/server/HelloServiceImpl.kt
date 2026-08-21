package com.portfolio.grpc.server

import com.portfolio.grpc.hello.HelloReply
import com.portfolio.grpc.hello.HelloRequest
import com.portfolio.grpc.hello.HelloServiceGrpcKt
import net.devh.boot.grpc.server.service.GrpcService

@GrpcService
class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {

    override suspend fun sayHello(request: HelloRequest): HelloReply {
        return HelloReply.newBuilder()
            .setMessage("Hello, ${request.name}!")
            .build()
    }
}
