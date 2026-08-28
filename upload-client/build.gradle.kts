import com.google.protobuf.gradle.id

plugins {
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.saebak"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

val grpcVersion = "1.63.0"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "3.25.3"
val grpcSpringBootStarterVersion = "3.1.0.RELEASE"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")

    // grpc-spring-boot-starter: server(@GrpcService) + client(@GrpcClient) 지원
    implementation("net.devh:grpc-spring-boot-starter:$grpcSpringBootStarterVersion")

    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")

    // grpc.health.v1.Health stub — 클라이언트가 서버 헬스 상태를 조회하는 데 사용
    implementation("io.grpc:grpc-services:$grpcVersion")

    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.grpc:grpc-testing:$grpcVersion")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/java", "build/generated/source/proto/main/grpc", "build/generated/source/proto/main/grpckt")
        }
    }
}
