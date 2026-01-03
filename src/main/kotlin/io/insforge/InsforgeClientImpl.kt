package io.insforge

import io.insforge.auth.Auth
import io.insforge.http.InsforgeHttpClient
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.PluginManager
import io.ktor.client.*

/**
 * Internal implementation of InsforgeClient
 */
internal class InsforgeClientImpl(
    override val config: InsforgeClientConfig
) : InsforgeClient {

    override val baseURL: String = config.baseURL
    override val anonKey: String = config.anonKey

    override val httpClient: HttpClient by lazy {
        InsforgeHttpClient.create(this)
    }

    override val pluginManager: PluginManager by lazy {
        PluginManager(
            config.plugins.mapValues { (_, factory) ->
                factory(this)
            }
        )
    }

    init {
        // Initialize all plugins
        pluginManager.installedPlugins.values.forEach { plugin ->
            plugin.init()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : InsforgePlugin<*>> plugin(key: String): T {
        return pluginManager.getPlugin(key) as? T
            ?: throw IllegalStateException("Plugin '$key' is not installed. Did you forget to call install($key)?")
    }

    override fun getCurrentAccessToken(): String? {
        return try {
            val auth = pluginManager.getPlugin("auth") as? Auth
            auth?.currentSession?.value?.accessToken
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        httpClient.close()
        pluginManager.close()
    }
}
