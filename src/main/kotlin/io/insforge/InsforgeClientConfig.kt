package io.insforge

import io.insforge.logging.InsforgeLogLevel
import io.insforge.plugins.InsforgePlugin
import io.ktor.client.engine.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration

/**
 * Configuration for InsforgeClient
 */
data class InsforgeClientConfig(
    val baseURL: String,
    val anonKey: String,
    val useHTTPS: Boolean,
    val httpEngine: HttpClientEngine?,
    val logLevel: InsforgeLogLevel,
    val requestTimeout: Duration,
    val coroutineDispatcher: CoroutineDispatcher,
    val accessToken: (() -> String?)?,
    val customHeaders: Map<String, String>,
    val plugins: Map<String, (InsforgeClient) -> InsforgePlugin<*>>
)
