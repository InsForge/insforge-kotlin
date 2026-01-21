package dev.insforge.http

import dev.insforge.InsforgeClient
import dev.insforge.InsforgeVersion
import dev.insforge.auth.Auth
import dev.insforge.logging.InsforgeLogLevel
import dev.insforge.logging.InsforgeLogger
import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Factory for creating configured HTTP clients
 */
object InsforgeHttpClient {

    // Mutex to prevent multiple simultaneous refresh attempts
    private val refreshMutex = Mutex()

    // Flag to prevent infinite refresh loops
    private var isRefreshing = false

    fun create(insforgeClient: InsforgeClient): HttpClient {
        val config = insforgeClient.config

        // Use OkHttp as default engine - works on both JVM and Android
        val engine = config.httpEngine ?: OkHttp.create()

        return HttpClient(engine) {
            // Content negotiation with JSON
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            // Logging - uses Napier via InsforgeHttpLogger
            // DEBUG level: logs request method/URL and response status
            // VERBOSE level: logs full headers and body content
            if (config.logLevel != InsforgeLogLevel.NONE) {
                install(Logging) {
                    logger = InsforgeHttpLogger()
                    level = config.logLevel.toKtorLogLevel()
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }

            // WebSocket support
            install(WebSockets)

            // Default request configuration
            install(DefaultRequest) {
                headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                headers.append(HttpHeaders.UserAgent, InsforgeVersion.USER_AGENT)

                // Add custom headers
                config.customHeaders.forEach { (key, value) ->
                    headers.append(key, value)
                }
            }

            // Timeout configuration
            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
                connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            }

            // Expect success (throw on non-2xx responses)
            expectSuccess = false // We'll handle errors manually for better error messages
        }.also { client ->
            // Add request interceptor to dynamically set Authorization header
            // and handle 401 responses with automatic token refresh
            client.plugin(HttpSend).intercept { request ->
                // Set Authorization header if not already set
                if (!request.headers.contains(HttpHeaders.Authorization)) {
                    // Priority: JWT token > custom accessToken provider > anonKey
                    val token = insforgeClient.getCurrentAccessToken()
                        ?: config.accessToken?.invoke()
                        ?: config.anonKey

                    request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                }

                val response = execute(request)

                // Handle 401 Unauthorized - try to refresh token and retry
                if (response.response.status == HttpStatusCode.Unauthorized && !isRefreshing) {
                    // Check if Auth plugin is installed and has a refresh token
                    val auth = try {
                        insforgeClient.pluginManager.getPlugin("auth") as? Auth
                    } catch (e: Exception) {
                        null
                    }

                    val hasRefreshToken = auth?.currentSession?.value?.refreshToken != null

                    if (auth != null && hasRefreshToken) {
                        // Skip auto-refresh for authentication endpoints that don't need it:
                        // - /api/auth/refresh: would cause infinite loop
                        // - /api/auth/users (POST): signup doesn't need refresh
                        // - /api/auth/sessions (POST): signin doesn't need refresh
                        // Other auth endpoints (getCurrentUser, updateProfile, etc.) should support auto-refresh
                        val path = request.url.encodedPath
                        val isRefreshEndpoint = path.contains("/api/auth/refresh")
                        val isSignUpEndpoint = path.contains("/api/auth/users") && request.method == HttpMethod.Post
                        val isSignInEndpoint = path.contains("/api/auth/sessions") && request.method == HttpMethod.Post
                        val skipAutoRefresh = isRefreshEndpoint || isSignUpEndpoint || isSignInEndpoint
                        if (!skipAutoRefresh) {
                            try {
                                val refreshSuccessful = refreshMutex.withLock {
                                    if (isRefreshing) {
                                        // Another coroutine is already refreshing
                                        false
                                    } else {
                                        isRefreshing = true
                                        try {
                                            auth.refreshAccessToken()
                                            InsforgeLogger.debug("Token refreshed successfully, retrying request", "HTTP")
                                            true
                                        } catch (e: Exception) {
                                            InsforgeLogger.warn("Token refresh failed: ${e.message}", tag = "HTTP")
                                            false
                                        } finally {
                                            isRefreshing = false
                                        }
                                    }
                                }

                                if (refreshSuccessful) {
                                    // Retry the request with the new token
                                    val newToken = insforgeClient.getCurrentAccessToken()
                                    if (newToken != null) {
                                        // Create a new request with the updated token
                                        val retryRequest = HttpRequestBuilder().apply {
                                            takeFrom(request)
                                            headers.remove(HttpHeaders.Authorization)
                                            headers.append(HttpHeaders.Authorization, "Bearer $newToken")
                                        }
                                        return@intercept execute(retryRequest)
                                    }
                                }
                            } catch (e: Exception) {
                                InsforgeLogger.warn("Auto-refresh failed: ${e.message}", tag = "HTTP")
                            }
                        }
                    }
                }

                response
            }
        }
    }
}
