package dev.insforge.plugins

import dev.insforge.InsforgeClient
import dev.insforge.InsforgeClientBuilder

/**
 * Base interface for all Insforge plugins/modules
 */
interface InsforgePlugin<out Config> {
    /**
     * Unique key identifying this plugin
     */
    val key: String

    /**
     * Initialize the plugin (called after client creation)
     */
    fun init() {}

    /**
     * Close and clean up resources
     */
    fun close() {}
}

/**
 * Provider interface for creating plugin instances
 */
interface InsforgePluginProvider<Config, PluginInstance : InsforgePlugin<Config>> {
    /**
     * Unique key for this plugin
     */
    val key: String

    /**
     * Create plugin configuration
     */
    fun createConfig(configure: Config.() -> Unit): Config

    /**
     * Setup phase (runs during builder configuration)
     */
    fun setup(builder: InsforgeClientBuilder, config: Config) {}

    /**
     * Create the plugin instance
     */
    fun create(client: InsforgeClient, config: Config): PluginInstance
}
