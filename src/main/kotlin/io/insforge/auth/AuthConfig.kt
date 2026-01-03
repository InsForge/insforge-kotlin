package io.insforge.auth

/**
 * Interface for launching URLs in the system browser.
 *
 * On Android, implement this interface using Intent:
 * ```kotlin
 * val browserLauncher = object : BrowserLauncher {
 *     override fun launch(url: String) {
 *         val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
 *         context.startActivity(intent)
 *     }
 * }
 * ```
 *
 * On Desktop/JVM, you can use Desktop.browse():
 * ```kotlin
 * val browserLauncher = object : BrowserLauncher {
 *     override fun launch(url: String) {
 *         Desktop.getDesktop().browse(URI(url))
 *     }
 * }
 * ```
 */
fun interface BrowserLauncher {
    /**
     * Launch a URL in the system browser
     * @param url The URL to open
     */
    fun launch(url: String)
}

/**
 * Configuration for the Auth module
 */
class AuthConfig {
    /**
     * Browser launcher for opening OAuth URLs.
     * Must be set before calling signInWithDefaultPage().
     *
     * Example (Android):
     * ```kotlin
     * val client = createInsforgeClient(baseURL, anonKey) {
     *     install(Auth) {
     *         browserLauncher = BrowserLauncher { url ->
     *             val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
     *             context.startActivity(intent)
     *         }
     *     }
     * }
     * ```
     */
    var browserLauncher: BrowserLauncher? = null

    /**
     * Whether to automatically save session to persistent storage.
     * Default is false - sessions are only kept in memory.
     */
    var persistSession: Boolean = false

    /**
     * Session storage implementation for persisting tokens.
     * Required if persistSession is true.
     */
    var sessionStorage: SessionStorage? = null
}

/**
 * Interface for persisting session data.
 *
 * On Android, implement using SharedPreferences or DataStore:
 * ```kotlin
 * val sessionStorage = object : SessionStorage {
 *     private val prefs = context.getSharedPreferences("insforge_auth", Context.MODE_PRIVATE)
 *
 *     override suspend fun save(key: String, value: String) {
 *         prefs.edit().putString(key, value).apply()
 *     }
 *
 *     override suspend fun get(key: String): String? {
 *         return prefs.getString(key, null)
 *     }
 *
 *     override suspend fun remove(key: String) {
 *         prefs.edit().remove(key).apply()
 *     }
 * }
 * ```
 */
interface SessionStorage {
    /**
     * Save a value to persistent storage
     */
    suspend fun save(key: String, value: String)

    /**
     * Get a value from persistent storage
     */
    suspend fun get(key: String): String?

    /**
     * Remove a value from persistent storage
     */
    suspend fun remove(key: String)
}
