# InsForge Kotlin SDK

Official Kotlin SDK for InsForge - A modern Backend-as-a-Service platform.

[![Maven Central](https://img.shields.io/maven-central/v/dev.insforge/insforge-kotlin)](https://central.sonatype.com/artifact/dev.insforge/insforge-kotlin)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Features

- 🔐 **Authentication** - Email/password, OAuth, email verification, password reset
- 📊 **Database** - PostgREST-style API with type-safe queries
- 📦 **Storage** - S3-compatible object storage with presigned URLs
- ⚡ **Functions** - Serverless functions in Deno runtime
- 🔄 **Realtime** - WebSocket pub/sub channels via Socket.IO
- 🤖 **AI** - Chat completion and image generation via OpenRouter

## Installation

### Maven Central (Recommended)

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.insforge:insforge-kotlin:0.1.7")
}
```

### GitHub Packages

```kotlin
// build.gradle.kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/InsForge/insforge-kotlin")
        credentials {
            username = "your-github-username"
            password = "your-github-token"  // needs read:packages permission
        }
    }
}

dependencies {
    implementation("dev.insforge:insforge-kotlin:0.1.7")
}
```

### Build from Source

```bash
git clone https://github.com/InsForge/insforge-kotlin.git
cd insforge-kotlin
./gradlew publishToMavenLocal
```

```kotlin
// build.gradle.kts
repositories {
    mavenLocal()
}

dependencies {
    implementation("dev.insforge:insforge-kotlin:0.1.7")
}
```

## Quick Start

```kotlin
import dev.insforge.createInsforgeClient
import dev.insforge.auth.*
import dev.insforge.database.*
import dev.insforge.storage.*
import dev.insforge.functions.*
import dev.insforge.realtime.*
import dev.insforge.ai.*

val client = createInsforgeClient(
    baseURL = "https://your-project.insforge.io",
    anonKey = "your-anon-key"
) {
    install(Auth)
    install(Database)
    install(Storage)
    install(Functions)
    install(Realtime)
    install(AI)
}

// Authentication
client.auth.signIn("user@example.com", "password123")

// Database - typed queries
@Serializable
data class Post(val id: String, val title: String, val published: Boolean)

val posts = client.database.from("posts")
    .select()
    .eq("published", true)
    .execute<Post>()

// Database - raw queries (for joins/nested data)
val rawData = client.database.from("posts")
    .select("id,title,author!posts_author_id_fkey(name)")
    .executeRaw()

// Storage
val result = client.storage.from("images").upload("photo.jpg", bytes) {
    contentType = "image/jpeg"
}

// Functions
val response = client.functions.invoke<MyResponse>("hello-world", request)

// Realtime
client.realtime.connect()
client.realtime.subscribe("chat:room1")
client.realtime.on("message") { msg ->
    println(msg.payload)
}

// AI
val chatResponse = client.ai.chatCompletion(
    model = "openai/gpt-4",
    messages = listOf(ChatMessage("user", "Hello!"))
)
```

## Documentation

- 📖 [Getting Started](GETTING_STARTED.md) - Quick start guide with detailed examples

## Project Structure

```
src/main/kotlin/dev/insforge/
├── InsforgeClient.kt          # Core client
├── auth/                      # Authentication module
├── database/                  # Database module
├── storage/                   # Storage module
├── functions/                 # Functions module
├── realtime/                  # Realtime module
├── ai/                        # AI module
├── plugins/                   # Plugin system
├── http/                      # HTTP client (Ktor + OkHttp)
├── logging/                   # Logging (Napier)
└── exceptions/                # Error handling
```

## Build & Development

```bash
# Build
./gradlew clean build

# Run unit tests (CI-safe, no external service dependency)
./gradlew test

# Run integration tests (requires InsForge test backend)
./gradlew integrationTest

# Publish to local Maven
./gradlew publishToMavenLocal
```

## Release Process

Tags are the source of truth for package versions. Stable and prerelease tags
publish to Maven Central and GitHub Packages; only stable tags create GitHub
Releases.

```bash
# Stable: publishes both packages, then creates a GitHub Release
git tag -a v0.1.8 -m "Release v0.1.8"
git push origin v0.1.8

# Prerelease: publishes both packages without a GitHub Release
git tag -a v0.1.8-beta.1 -m "Release v0.1.8-beta.1"
git push origin v0.1.8-beta.1
```

The publish workflow derives the Gradle version from the tag, runs the unit
tests, signs the artifacts, and publishes them. Do not create the GitHub Release
manually; the workflow does that after both stable package publishes succeed.

Maven Central currently requires a Portal user token and GPG signing key, stored
as `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `GPG_KEY_ID`,
`GPG_PRIVATE_KEY`, and `GPG_PASSPHRASE` repository secrets. GitHub Packages uses
the workflow's short-lived `GITHUB_TOKEN`; no additional GitHub token or Actions
environment is required. Rotate the Maven Central token before its configured
expiration date.

## Requirements

- Java 11+
- Kotlin 1.9.22+

## Tech Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Language | Kotlin | 1.9.22 |
| HTTP Client | Ktor + OkHttp | 2.3.7 |
| JSON | Kotlinx Serialization | 1.6.2 |
| Async | Kotlinx Coroutines | 1.7.3 |
| Logging | Napier | 2.7.1 |
| Realtime | Socket.IO | 2.1.1 |

## Modules

| Module | Features |
|--------|----------|
| Auth | Sign up/in, Email verification, Password reset, OAuth, Session persistence |
| Database | CRUD, Query builder, Type-safe queries, Raw queries for joins |
| Storage | Upload/Download, Buckets, Presigned URLs, S3 compatible |
| Functions | Invoke serverless functions |
| Realtime | Pub/sub channels, Connection state management |
| AI | Chat completion, Image generation, Streaming |

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](./LICENSE) file for details.

## Links

- [InsForge Platform](https://insforge.dev)
- [GitHub Repository](https://github.com/InsForge/insforge-kotlin)
- [Maven Central](https://central.sonatype.com/artifact/dev.insforge/insforge-kotlin)
