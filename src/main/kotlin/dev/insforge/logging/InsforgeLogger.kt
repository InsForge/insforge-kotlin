package dev.insforge.logging

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier

/**
 * InsForge SDK logging levels.
 *
 * Log levels follow standard severity ordering:
 * - NONE: No logging
 * - ERROR: Only errors
 * - WARN: Warnings and errors
 * - INFO: Informational messages
 * - DEBUG: Debug information (request/response status)
 * - VERBOSE: Detailed trace information (full request/response bodies)
 */
enum class InsforgeLogLevel {
    NONE,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    VERBOSE
}

/**
 * Central logging utility for InsForge SDK.
 *
 * Uses Napier internally for cross-platform logging support (JVM/Android/iOS).
 *
 * Usage:
 * ```kotlin
 * // Initialize once at app startup
 * InsforgeLogger.initialize(InsforgeLogLevel.DEBUG)
 *
 * // Or with custom antilog
 * InsforgeLogger.initialize(InsforgeLogLevel.DEBUG, customAntilog)
 *
 * // Log messages
 * InsforgeLogger.debug("Request completed")
 * InsforgeLogger.verbose("Response body: $body")
 * ```
 */
object InsforgeLogger {

    @PublishedApi
    internal const val TAG = "InsForge"

    private var currentLogLevel: InsforgeLogLevel = InsforgeLogLevel.NONE
    private var isInitialized = false

    /**
     * Initialize the logger with specified log level.
     *
     * @param level The minimum log level to output
     * @param antilog Custom Napier antilog implementation (defaults to DebugAntilog)
     */
    fun initialize(
        level: InsforgeLogLevel = InsforgeLogLevel.DEBUG,
        antilog: Antilog = DebugAntilog()
    ) {
        if (!isInitialized) {
            Napier.base(antilog)
            isInitialized = true
        }
        currentLogLevel = level
    }

    /**
     * Set the log level without reinitializing.
     */
    fun setLogLevel(level: InsforgeLogLevel) {
        currentLogLevel = level
    }

    /**
     * Get the current log level.
     */
    fun getLogLevel(): InsforgeLogLevel = currentLogLevel

    /**
     * Check if a log level is enabled.
     */
    fun isEnabled(level: InsforgeLogLevel): Boolean {
        return level != InsforgeLogLevel.NONE &&
               currentLogLevel != InsforgeLogLevel.NONE &&
               level.ordinal <= currentLogLevel.ordinal
    }

    // ============ Logging Methods ============

    /**
     * Log verbose/trace message.
     * Use for detailed information like full request/response bodies.
     */
    fun verbose(message: String, tag: String = TAG) {
        if (isEnabled(InsforgeLogLevel.VERBOSE)) {
            Napier.v(message, tag = tag)
        }
    }

    /**
     * Log verbose/trace message with lazy evaluation.
     */
    inline fun verbose(tag: String = TAG, message: () -> String) {
        if (isEnabled(InsforgeLogLevel.VERBOSE)) {
            Napier.v(message(), tag = tag)
        }
    }

    /**
     * Log debug message.
     * Use for request/response status, method calls.
     */
    fun debug(message: String, tag: String = TAG) {
        if (isEnabled(InsforgeLogLevel.DEBUG)) {
            Napier.d(message, tag = tag)
        }
    }

    /**
     * Log debug message with lazy evaluation.
     */
    inline fun debug(tag: String = TAG, message: () -> String) {
        if (isEnabled(InsforgeLogLevel.DEBUG)) {
            Napier.d(message(), tag = tag)
        }
    }

    /**
     * Log info message.
     */
    fun info(message: String, tag: String = TAG) {
        if (isEnabled(InsforgeLogLevel.INFO)) {
            Napier.i(message, tag = tag)
        }
    }

    /**
     * Log info message with lazy evaluation.
     */
    inline fun info(tag: String = TAG, message: () -> String) {
        if (isEnabled(InsforgeLogLevel.INFO)) {
            Napier.i(message(), tag = tag)
        }
    }

    /**
     * Log warning message.
     */
    fun warn(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (isEnabled(InsforgeLogLevel.WARN)) {
            Napier.w(message, throwable, tag = tag)
        }
    }

    /**
     * Log warning message with lazy evaluation.
     */
    inline fun warn(throwable: Throwable? = null, tag: String = TAG, message: () -> String) {
        if (isEnabled(InsforgeLogLevel.WARN)) {
            Napier.w(message(), throwable, tag = tag)
        }
    }

    /**
     * Log error message.
     */
    fun error(message: String, throwable: Throwable? = null, tag: String = TAG) {
        if (isEnabled(InsforgeLogLevel.ERROR)) {
            Napier.e(message, throwable, tag = tag)
        }
    }

    /**
     * Log error message with lazy evaluation.
     */
    inline fun error(throwable: Throwable? = null, tag: String = TAG, message: () -> String) {
        if (isEnabled(InsforgeLogLevel.ERROR)) {
            Napier.e(message(), throwable, tag = tag)
        }
    }
}

/**
 * Extension to create a tagged logger for a specific module.
 */
class TaggedLogger(@PublishedApi internal val tag: String) {

    fun verbose(message: String) = InsforgeLogger.verbose(message, tag)
    inline fun verbose(message: () -> String) = InsforgeLogger.verbose(tag, message)

    fun debug(message: String) = InsforgeLogger.debug(message, tag)
    inline fun debug(message: () -> String) = InsforgeLogger.debug(tag, message)

    fun info(message: String) = InsforgeLogger.info(message, tag)
    inline fun info(message: () -> String) = InsforgeLogger.info(tag, message)

    fun warn(message: String, throwable: Throwable? = null) = InsforgeLogger.warn(message, throwable, tag)
    inline fun warn(throwable: Throwable? = null, message: () -> String) = InsforgeLogger.warn(throwable, tag, message)

    fun error(message: String, throwable: Throwable? = null) = InsforgeLogger.error(message, throwable, tag)
    inline fun error(throwable: Throwable? = null, message: () -> String) = InsforgeLogger.error(throwable, tag, message)
}

/**
 * Create a tagged logger for a specific module.
 */
fun insforgeLogger(tag: String): TaggedLogger = TaggedLogger(tag)
