# Getting Started with InsForge Kotlin SDK

## Quick Start

### Installation

**Gradle (Kotlin DSL):**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.insforge:insforge-kotlin:0.1.7")
}
```

**Gradle (Groovy):**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'dev.insforge:insforge-kotlin:0.1.7'
}
```

### Basic Usage

```kotlin
import dev.insforge.createInsforgeClient
import dev.insforge.auth.Auth
import dev.insforge.auth.auth
import dev.insforge.database.Database
import dev.insforge.database.database
import dev.insforge.storage.Storage
import dev.insforge.storage.storage
import dev.insforge.logging.InsforgeLogLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable

@Serializable
data class Post(
    val id: String? = null,
    val title: String,
    val content: String
)

fun main() = runBlocking {
    // Create client
    val client = createInsforgeClient(
        baseURL = "https://your-project.insforge.app",
        anonKey = "your-anon-key"
    ) {
        // Configure log level (NONE, ERROR, WARN, INFO, DEBUG, VERBOSE)
        logLevel = InsforgeLogLevel.DEBUG

        // Install plugins
        install(Auth)
        install(Database)
        install(Storage)
    }

    try {
        // 1. Authentication
        println("=== Authentication ===")
        val response = client.auth.signUp(
            email = "test@example.com",
            password = "password123",
            name = "Test User"
        )
        println("User created: ${response.user.email}")

        // 2. Database operations
        println("\n=== Database ===")

        // Insert data
        client.database.from("posts")
            .insert(listOf(
                Post(title = "Hello Kotlin", content = "First post"),
                Post(title = "InsForge SDK", content = "Second post")
            ))
            .execute<Post>()
        println("Posts inserted")

        // Query data
        val posts = client.database.from("posts")
            .select()
            .like("title", "%Kotlin%")
            .execute<Post>()
        println("Found ${posts.size} posts")

        // 3. Storage
        println("\n=== Storage ===")

        // Create bucket
        client.storage.createBucket("images", isPublic = true)
        println("Bucket created")

        // Upload file
        val imageBytes = "fake-image-data".toByteArray()
        val uploaded = client.storage.from("images")
            .upload(imageBytes, "test.jpg", "image/jpeg")
        println("File uploaded: ${uploaded.url}")

    } catch (e: Exception) {
        println("Error: ${e.message}")
    } finally {
        client.close()
    }
}
```

## Project Structure

```
src/main/kotlin/dev/insforge/
├── InsforgeClient.kt              # Main client interface
├── InsforgeClientBuilder.kt       # Builder pattern configuration
├── InsforgeClientImpl.kt          # Client implementation
├── InsforgeClientConfig.kt        # Configuration class
│
├── auth/                          # Authentication module
│   ├── Auth.kt                    # Auth functionality
│   ├── AuthConfig.kt              # Auth configuration
│   └── models/                    # Data models
│
├── database/                      # Database module
│   ├── Database.kt                # Database operations
│   ├── DatabaseConfig.kt          # Database configuration
│   ├── TableQuery.kt              # Query builder
│   └── models/                    # Data models
│
├── storage/                       # Storage module
│   ├── Storage.kt                 # Storage operations
│   ├── BucketApi.kt               # Bucket API
│   ├── StorageConfig.kt           # Storage configuration
│   └── models/                    # Data models
│
├── functions/                     # Edge Functions module
│   ├── Functions.kt               # Function invocation
│   └── models/                    # Data models
│
├── realtime/                      # Realtime module
│   ├── Realtime.kt                # WebSocket/Socket.IO
│   ├── InsforgeChannel.kt         # Channel abstraction
│   └── models/                    # Data models
│
├── ai/                            # AI module
│   ├── AI.kt                      # Chat & image generation
│   └── models/                    # Data models
│
├── logging/                       # Logging utilities
│   ├── InsforgeLogger.kt          # Napier-based logger
│   └── InsforgeHttpLogger.kt      # HTTP request/response logging
│
├── plugins/                       # Plugin system
│   ├── InsforgePlugin.kt          # Plugin interface
│   └── PluginManager.kt           # Plugin manager
│
├── http/                          # HTTP client
│   └── InsforgeHttpClient.kt      # Ktor HTTP configuration
│
└── exceptions/                    # Exception handling
    └── InsforgeException.kt       # Custom exceptions
```

## Features

### Authentication
- User registration/login
- Passwordless email OTP sign-in
- Email verification (OTP + magic link)
- Password reset
- OAuth integration (Google, GitHub, etc.)
- Session management with StateFlow

### Database
- PostgREST-style query API
- Type-safe CRUD operations
- Query builder (select, filter, order, limit, offset)
- Table schema management

### Storage
- S3-compatible object storage
- File upload/download
- Bucket management
- Presigned URL support
- Public/private access control

### Functions
- Edge function invocation
- Custom request/response handling

### Realtime
- Socket.IO based pub/sub
- Channel subscriptions
- Postgres change notifications
- Broadcast messaging

### AI
- Chat completions
- Image generation
- Streaming responses

## Configuration Options

### Log Levels

```kotlin
val client = createInsforgeClient(baseURL, anonKey) {
    // NONE - No logging
    // ERROR - Only errors
    // WARN - Warnings and errors
    // INFO - Informational messages
    // DEBUG - Request method/URL, response status
    // VERBOSE - Full headers and request/response bodies
    logLevel = InsforgeLogLevel.DEBUG
}
```

### Custom Timeout

```kotlin
val client = createInsforgeClient(baseURL, anonKey) {
    requestTimeout = 30.seconds
}
```

### Dynamic Token

```kotlin
val client = createInsforgeClient(baseURL, anonKey) {
    accessToken = {
        // Get current token from your session manager
        mySessionManager.getToken()
    }
}
```

### Custom Headers

```kotlin
val client = createInsforgeClient(baseURL, anonKey) {
    addHeader("X-App-Version", "1.0.0")
    addHeader("X-Device-ID", deviceId)
}
```

## Android Integration

For Android projects, add the dependency to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("dev.insforge:insforge-kotlin:0.1.7")
}
```

Initialize in your Application class or using dependency injection:

```kotlin
class MyApplication : Application() {
    lateinit var insforgeClient: InsforgeClient

    override fun onCreate() {
        super.onCreate()

        insforgeClient = createInsforgeClient(
            baseURL = BuildConfig.INSFORGE_URL,
            anonKey = BuildConfig.INSFORGE_ANON_KEY
        ) {
            logLevel = if (BuildConfig.DEBUG)
                InsforgeLogLevel.DEBUG
            else
                InsforgeLogLevel.NONE

            install(Auth) {
                sessionStorage = DataStoreSessionStorage(applicationContext)
                browserLauncher = { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            install(Database)
            install(Storage)
            install(Realtime)
        }
    }
}
```

## Development

### Build

```bash
# Clean and build
./gradlew clean build

# Build without tests
./gradlew build -x test

# Run tests
./gradlew test
```

### Publish

```bash
# Publish to local Maven repository
./gradlew publishToMavenLocal

# Run the same version override used by the tag publish workflow
./gradlew -Pversion=0.1.8 publishToMavenLocal
```

## Requirements

- JVM 11 or higher
- Kotlin 1.9+
- For Android: minSdk 24

## Dependencies

- Ktor Client (OkHttp engine)
- Kotlinx Serialization
- Kotlinx Coroutines
- Napier (logging)
- Socket.IO Client (realtime)

## License

MIT License
