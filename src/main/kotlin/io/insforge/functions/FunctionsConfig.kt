package io.insforge.functions

/**
 * Configuration for the Functions module
 */
class FunctionsConfig {
    /**
     * Default timeout for function invocations (in milliseconds)
     */
    var invokeTimeout: Long = 60000 // 60 seconds

    /**
     * Enable function execution logging
     */
    var enableLogging: Boolean = false
}
