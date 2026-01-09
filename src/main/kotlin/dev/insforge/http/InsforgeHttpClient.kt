package dev.insforge.http

import dev.insforge.InsforgeClient
import dev.insforge.InsforgeVersion
import dev.insforge.logging.InsforgeLogLevel
import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Factory for creating configured HTTP clients
 */
object InsforgeHttpClient {

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
            // Uses JWT token if user is logged in, otherwise falls back to anonKey
            client.plugin(HttpSend).intercept { request ->
                if (!request.headers.contains(HttpHeaders.Authorization)) {
                    // Priority: JWT token > custom accessToken provider > anonKey
                    val token = insforgeClient.getCurrentAccessToken()
                        ?: config.accessToken?.invoke()
                        ?: config.anonKey

                    request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                }

                execute(request)
            }
        }
    }
}
