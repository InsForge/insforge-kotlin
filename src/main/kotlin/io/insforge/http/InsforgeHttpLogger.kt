package io.insforge.http

import io.insforge.logging.InsforgeLogLevel
import io.insforge.logging.InsforgeLogger
import io.ktor.client.plugins.logging.*

/**
 * Custom Ktor HTTP logger that integrates with InsforgeLogger.
 *
 * Logging levels:
 * - DEBUG: Request method, URL, and response status code
 * - VERBOSE: Full headers and body content
 *
 * This matches the Swift SDK logging behavior:
 * ```swift
 * logger.debug("POST \(endpoint)")
 * logger.trace("Request headers: \(headers)")
 * logger.trace("Request body: \(body)")
 * logger.debug("Response: \(statusCode)")
 * logger.trace("Response body: \(responseBody)")
 * ```
 */
internal class InsforgeHttpLogger : Logger {

    companion object {
        private const val TAG = "InsForge.HTTP"
    }

    override fun log(message: String) {
        // Ktor logging plugin sends multi-line messages
        // We parse and route them to appropriate log levels
        val lines = message.lines()

        for (line in lines) {
            when {
                // Request line: "REQUEST: https://..." or "METHOD: https://..."
                line.startsWith("REQUEST:") ||
                line.startsWith("GET ") ||
                line.startsWith("POST ") ||
                line.startsWith("PUT ") ||
                line.startsWith("PATCH ") ||
                line.startsWith("DELETE ") ||
                line.startsWith("HEAD ") ||
                line.startsWith("OPTIONS ") -> {
                    InsforgeLogger.debug(line.trim(), TAG)
                }

                // Response status: "RESPONSE: 200 OK" or similar
                line.startsWith("RESPONSE:") ||
                line.contains(Regex("^\\d{3}\\s")) -> {
                    InsforgeLogger.debug(line.trim(), TAG)
                }

                // Headers and body go to verbose
                line.startsWith("COMMON HEADERS") ||
                line.startsWith("CONTENT HEADERS") ||
                line.startsWith("BODY START") ||
                line.startsWith("BODY END") ||
                line.startsWith("BODY Content-Type:") ||
                line.startsWith("->") ||
                line.startsWith("<-") -> {
                    InsforgeLogger.verbose(line.trim(), TAG)
                }

                // Body content (usually indented or after BODY START)
                line.isNotBlank() -> {
                    // Check if this looks like body content (JSON, etc.)
                    val trimmed = line.trim()
                    if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<")) {
                        InsforgeLogger.verbose(trimmed, TAG)
                    } else if (trimmed.isNotEmpty()) {
                        // Default to verbose for anything else
                        InsforgeLogger.verbose(trimmed, TAG)
                    }
                }
            }
        }
    }
}

/**
 * Convert InsforgeLogLevel to Ktor LogLevel.
 */
internal fun InsforgeLogLevel.toKtorLogLevel(): LogLevel {
    return when (this) {
        InsforgeLogLevel.NONE -> LogLevel.NONE
        InsforgeLogLevel.ERROR -> LogLevel.NONE  // Ktor doesn't have ERROR level
        InsforgeLogLevel.WARN -> LogLevel.NONE   // Ktor doesn't have WARN level
        InsforgeLogLevel.INFO -> LogLevel.INFO
        InsforgeLogLevel.DEBUG -> LogLevel.INFO  // DEBUG shows method + status
        InsforgeLogLevel.VERBOSE -> LogLevel.ALL // VERBOSE shows everything
    }
}
