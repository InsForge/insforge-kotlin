package dev.insforge

import dev.insforge.plugins.InsforgePlugin
import dev.insforge.plugins.PluginManager
import io.ktor.client.*

/**
 * Main Insforge client interface.
 *
 * Create an instance using [createInsforgeClient]:
 * ```kotlin
 * val client = createInsforgeClient(
 *     baseURL = "https://your-project.insforge.io",
 *     anonKey = "your-anon-key"
 * ) {
 *     install(Auth) { }
 *     install(Database) { }
 *     install(Storage) { }
 * }
 * ```
 */
interface InsforgeClient {
    /**
     * The base URL of your Insforge project (without trailing slash)
     */
    val baseURL: String

    /**
     * Anonymous key for authentication
     */
    val anonKey: String

    /**
     * HTTP client configuration
     */
    val config: InsforgeClientConfig

    /**
     * Underlying HTTP client
     */
    val httpClient: HttpClient

    /**
     * Plugin manager for installed modules
     */
    val pluginManager: PluginManager

    /**
     * Get an installed plugin by its key
     */
    fun <T : InsforgePlugin<*>> plugin(key: String): T

    /**
     * Get current access token from Auth module if available.
     * Returns null if Auth is not installed or user is not logged in.
     */
    fun getCurrentAccessToken(): String?

    /**
     * Close the client and release resources
     */
    fun close()
}

/**
 * Create a new Insforge client.
 *
 * @param baseURL The base URL of your Insforge project
 * @param anonKey Your project anonymous key
 * @param configure Additional configuration
 */
fun createInsforgeClient(
    baseURL: String,
    anonKey: String,
    configure: InsforgeClientBuilder.() -> Unit = {}
): InsforgeClient {
    val builder = InsforgeClientBuilder(baseURL, anonKey)
    builder.configure()
    return builder.build()
}
