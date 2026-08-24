package com.portfolio.grpc.upload

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UploadServerApplication

fun main(args: Array<String>) {
    runApplication<UploadServerApplication>(*args)
}
