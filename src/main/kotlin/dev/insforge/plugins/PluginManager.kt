package dev.insforge.plugins

/**
 * Manages the lifecycle of plugins installed on an [dev.insforge.InsforgeClient].
 *
 * Plugins are registered at client construction time and can be retrieved by their unique key.
 * Calling [close] will clean up resources for all installed plugins.
 */
class PluginManager(
    /**
     * A map of all installed plugins keyed by their unique plugin identifier.
     *
     * @see InsforgePlugin.key
     */
    val installedPlugins: Map<String, InsforgePlugin<*>>
) {
    /**
     * Returns the plugin registered under [key], or `null` if no such plugin is installed.
     *
     * @param key The unique identifier of the plugin.
     * @return The installed [InsforgePlugin], or `null` if not found.
     */
    fun getPlugin(key: String): InsforgePlugin<*>? {
        return installedPlugins[key]
    }

    /**
     * Returns `true` if a plugin with the given [key] is installed, `false` otherwise.
     *
     * @param key The unique identifier of the plugin.
     */
    fun hasPlugin(key: String): Boolean {
        return installedPlugins.containsKey(key)
    }

    /**
     * Closes all installed plugins and releases their resources.
     *
     * Delegates to [InsforgePlugin.close] on each installed plugin.
     */
    fun close() {
        installedPlugins.values.forEach { it.close() }
    }
}
