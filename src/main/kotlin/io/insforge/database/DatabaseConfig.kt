package io.insforge.database

/**
 * Configuration for the Database module
 */
class DatabaseConfig {
    /**
     * Default schema to use (if any)
     */
    var defaultSchema: String? = null

    /**
     * Enable query logging
     */
    var enableQueryLogging: Boolean = false
}
