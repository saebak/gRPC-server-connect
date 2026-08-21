package com.portfolio.grpc.client

import com.portfolio.grpc.hello.HelloRequest
import com.portfolio.grpc.hello.HelloServiceGrpcKt
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component

@Component
class HelloClientRunner(
    @GrpcClient("hello-service") private val stub: HelloServiceGrpcKt.HelloServiceCoroutineStub,
) : CommandLineRunner {

    override fun run(vararg args: String?): Unit = runBlocking {
        val request = HelloRequest.newBuilder().setName("world").build()
        val reply = stub.sayHello(request)
        println(">>> gRPC client received: ${reply.message}")
    }
}
