package io.insforge.plugins

/**
 * Manages installed plugins
 */
class PluginManager(
    val installedPlugins: Map<String, InsforgePlugin<*>>
) {
    /**
     * Get a plugin by key
     */
    fun getPlugin(key: String): InsforgePlugin<*>? {
        return installedPlugins[key]
    }

    /**
     * Check if a plugin is installed
     */
    fun hasPlugin(key: String): Boolean {
        return installedPlugins.containsKey(key)
    }

    /**
     * Close all plugins
     */
    fun close() {
        installedPlugins.values.forEach { it.close() }
    }
}
