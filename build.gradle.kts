plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    `maven-publish`
    id("pl.allegro.tech.build.axion-release") version "1.17.0"
    id("com.vanniktech.maven.publish") version "0.28.0"
}

group = "dev.insforge"
version = scmVersion.version

// Configure axion-release plugin
scmVersion {
    tag {
        prefix.set("v")
    }
    versionIncrementer("incrementPatch")
    checks {
        // Disable remote branch tracking check
        aheadOfRemote.set(false)
    }
}

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
            |package dev.insforge
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

// Ensure all Jar tasks depend on generateVersionFile
tasks.withType<Jar>().configureEach {
    dependsOn(generateVersionFile)
}

// Explicitly configure kotlinSourcesJar task created by vanniktech plugin
tasks.matching { it.name == "kotlinSourcesJar" }.configureEach {
    dependsOn(generateVersionFile)
}

// GitHub Packages repository configuration
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/InsForge/insforge-kotlin")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: project.findProperty("gpr.user") as String? ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: project.findProperty("gpr.token") as String? ?: ""
            }
        }
    }
}

// Maven Central publishing via Vanniktech plugin
mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates(
        groupId = "dev.insforge",
        artifactId = "insforge-kotlin",
        version = version.toString()
    )

    pom {
        name.set("InsForge Kotlin SDK")
        description.set("Official Kotlin SDK for InsForge Backend-as-a-Service")
        url.set("https://github.com/InsForge/insforge-kotlin")
        inceptionYear.set("2025")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("insforge")
                name.set("InsForge Team")
                email.set("support@insforge.dev")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/InsForge/insforge-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/InsForge/insforge-kotlin.git")
            url.set("https://github.com/InsForge/insforge-kotlin")
        }
    }
}

// Note: GPG signing is handled by the vanniktech maven-publish plugin via environment variables:
// - ORG_GRADLE_PROJECT_signingInMemoryKeyId
// - ORG_GRADLE_PROJECT_signingInMemoryKey
// - ORG_GRADLE_PROJECT_signingInMemoryKeyPassword

// Fix implicit dependency issues between signing and jar tasks
tasks.configureEach {
    if (name.startsWith("sign")) {
        dependsOn(tasks.withType<Jar>())
    }
}
