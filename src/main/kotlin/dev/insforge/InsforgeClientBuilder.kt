package dev.insforge

import dev.insforge.logging.InsforgeLogLevel
import dev.insforge.logging.InsforgeLogger
import dev.insforge.plugins.InsforgePlugin
import dev.insforge.plugins.InsforgePluginProvider
import io.ktor.client.engine.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Builder for configuring and creating an Insforge client.
 */
class InsforgeClientBuilder @PublishedApi internal constructor(
    private val baseURL: String,
    private val anonKey: String
) {
    /**
     * Whether to use HTTPS (default: true)
     */
    var useHTTPS: Boolean = true

    /**
     * Custom HTTP engine (optional)
     */
    var httpEngine: HttpClientEngine? = null

    /**
     * SDK logging level.
     *
     * Available levels:
     * - InsforgeLogLevel.NONE: No logging (default, recommended for production)
     * - InsforgeLogLevel.ERROR: Only errors
     * - InsforgeLogLevel.WARN: Warnings and errors
     * - InsforgeLogLevel.INFO: Informational messages
     * - InsforgeLogLevel.DEBUG: Debug info (request method, URL, response status)
     * - InsforgeLogLevel.VERBOSE: Full details (headers, request/response bodies)
     *
     * Example:
     * ```kotlin
     * val client = createInsforgeClient(url, key) {
     *     logLevel = InsforgeLogLevel.DEBUG  // Log request/response status
     *     // or
     *     logLevel = InsforgeLogLevel.VERBOSE  // Log everything including bodies
     * }
     * ```
     *
     * Output example (DEBUG):
     * ```
     * [InsForge.HTTP] POST https://example.com/api/database/records/todos
     * [InsForge.HTTP] RESPONSE: 201 Created
     * ```
     *
     * Output example (VERBOSE):
     * ```
     * [InsForge.HTTP] POST https://example.com/api/database/records/todos
     * [InsForge.HTTP] -> Content-Type: application/json
     * [InsForge.HTTP] -> Authorization: Bearer ***
     * [InsForge.HTTP] {"title":"Buy milk","completed":false}
     * [InsForge.HTTP] RESPONSE: 201 Created
     * [InsForge.HTTP] {"id":"123","title":"Buy milk","completed":false}
     * ```
     */
    var logLevel: InsforgeLogLevel = InsforgeLogLevel.NONE

    /**
     * Request timeout duration
     */
    var requestTimeout: Duration = 60.seconds

    /**
     * Coroutine dispatcher for async operations
     */
    var coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Custom access token provider (optional)
     * Useful for providing JWT tokens dynamically
     */
    var accessToken: (() -> String?)? = null

    /**
     * Custom headers to include in all requests
     */
    val customHeaders: MutableMap<String, String> = mutableMapOf()

    internal val plugins = mutableMapOf<String, (InsforgeClient) -> InsforgePlugin<*>>()

    init {
        // Validate URL doesn't contain module-specific paths
        val invalidPaths = listOf("/api/auth", "/api/database", "/api/storage", "/api/functions", "/api/realtime", "/api/ai")
        invalidPaths.forEach { path ->
            require(!baseURL.contains(path)) {
                "The Insforge URL should not contain module paths like '$path'. The SDK handles API endpoints automatically."
            }
        }
    }

    /**
     * Install a plugin/module.
     *
     * @param plugin The plugin provider
     * @param configure Configuration lambda for the plugin
     */
    fun <Config, PluginInstance : InsforgePlugin<Config>, Provider : InsforgePluginProvider<Config, PluginInstance>>
            install(
        plugin: Provider,
        configure: Config.() -> Unit = {}
    ) {
        val config = plugin.createConfig(configure)
        plugin.setup(this, config)
        plugins[plugin.key] = { plugin.create(it, config) }
    }

    /**
     * Add a custom header to all requests
     */
    fun addHeader(key: String, value: String) {
        customHeaders[key] = value
    }

    /**
     * Build the Insforge client
     */
    @PublishedApi
    internal fun build(): InsforgeClient {
        val normalizedUrl = baseURL.trimEnd('/')
        val finalBaseURL = if (useHTTPS && !normalizedUrl.startsWith("http")) {
            "https://$normalizedUrl"
        } else if (!useHTTPS && !normalizedUrl.startsWith("http")) {
            "http://$normalizedUrl"
        } else {
            normalizedUrl
        }

        val config = InsforgeClientConfig(
            baseURL = finalBaseURL,
            anonKey = anonKey,
            useHTTPS = useHTTPS,
            httpEngine = httpEngine,
            logLevel = logLevel,
            requestTimeout = requestTimeout,
            coroutineDispatcher = coroutineDispatcher,
            accessToken = accessToken,
            customHeaders = customHeaders.toMap(),
            plugins = plugins.toMap()
        )

        // Initialize logger with configured level
        InsforgeLogger.initialize(logLevel)

        return InsforgeClientImpl(config)
    }
}
