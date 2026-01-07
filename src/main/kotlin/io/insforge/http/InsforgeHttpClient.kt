package io.insforge.http

import io.insforge.InsforgeClient
import io.insforge.InsforgeVersion
import io.ktor.client.*
import io.ktor.client.engine.cio.*
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

        return HttpClient(config.httpEngine ?: CIO.create()) {
            // Content negotiation with JSON
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }

            // Logging - configurable HTTP request/response logging
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        println("[InsForge HTTP] $message")
                    }
                }
                level = config.defaultLogLevel
                // Note: No header sanitization - Authorization header is shown in full for debugging
                // In production, consider masking sensitive headers
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
