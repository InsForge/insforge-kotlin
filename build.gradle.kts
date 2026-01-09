plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    `maven-publish`
}

group = "io.insforge"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Kotlin stdlib
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Logging - Napier (cross-platform: JVM/Android/iOS)
    api("io.github.aakira:napier:2.7.1")

    // HTTP Client (Ktor)
    // OkHttp is the default engine - works on both JVM and Android
    api("io.ktor:ktor-client-core:2.3.7")
    api("io.ktor:ktor-client-okhttp:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-client-logging:2.3.7")

    // Socket.IO client for Realtime (InsForge uses Socket.IO, not raw WebSocket)
    implementation("io.socket:socket.io-client:2.1.1")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.ktor:ktor-client-mock:2.3.7")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

// Generate InsforgeVersion.kt with version from build.gradle.kts
val generateVersionFile by tasks.registering {
    val outputDir = file("$buildDir/generated/source/version/main/kotlin")
    val versionFile = file("$outputDir/io/insforge/InsforgeVersion.kt")

    inputs.property("version", version)
    outputs.file(versionFile)

    doLast {
        outputDir.mkdirs()
        versionFile.parentFile.mkdirs()
        versionFile.writeText(
            """
            |package io.insforge
            |
            |/**
            | * SDK version information (auto-generated from build.gradle.kts)
            | */
            |object InsforgeVersion {
            |    const val VERSION = "$version"
            |    const val USER_AGENT = "InsForge-Kotlin/${'$'}VERSION"
            |}
            """.trimMargin()
        )
    }
}

// Add generated source to main source set
kotlin {
    sourceSets {
        main {
            kotlin.srcDir("$buildDir/generated/source/version/main/kotlin")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    dependsOn(generateVersionFile)
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Show test output including HTTP logs
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("InsForge Kotlin SDK")
                description.set("Official Kotlin SDK for InsForge Backend-as-a-Service")
                url.set("https://github.com/insforge/insforge-kotlin")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
