package io.insforge

import io.insforge.http.InsforgeHttpClient
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.InsforgePluginProvider
import io.ktor.client.engine.*
import io.ktor.client.plugins.logging.*
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
     * Default log level for HTTP client
     */
    var defaultLogLevel: LogLevel = LogLevel.INFO

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
            defaultLogLevel = defaultLogLevel,
            requestTimeout = requestTimeout,
            coroutineDispatcher = coroutineDispatcher,
            accessToken = accessToken,
            customHeaders = customHeaders.toMap(),
            plugins = plugins.toMap()
        )

        return InsforgeClientImpl(config)
    }
}
