package io.insforge.samples.todo.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.insforge.InsforgeClient
import io.insforge.auth.Auth
import io.insforge.auth.BrowserLauncher
import io.insforge.auth.SessionStorage
import io.insforge.auth.auth
import io.insforge.createInsforgeClient
import io.insforge.database.Database
import io.insforge.database.database
import io.insforge.samples.todo.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// DataStore extension for the application context
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "insforge_auth")

/**
 * Singleton manager for InsForge client.
 *
 * Handles initialization with:
 * - Browser launcher for OAuth flows
 * - Session persistence using DataStore
 * - Database module for todo CRUD operations
 */
object InsforgeManager {

    private var _client: InsforgeClient? = null

    val client: InsforgeClient
        get() = _client ?: throw IllegalStateException(
            "InsforgeManager not initialized. Call initialize(context) first."
        )

    /**
     * OAuth callback URL scheme
     */
    const val OAUTH_CALLBACK_URL = "insforge-todo://auth/callback"

    /**
     * Initialize the InsForge client with Android-specific configuration.
     * Should be called from Application.onCreate()
     */
    fun initialize(context: Context) {
        if (_client != null) return

        val appContext = context.applicationContext

        _client = createInsforgeClient(
            baseURL = BuildConfig.INSFORGE_URL,
            anonKey = BuildConfig.INSFORGE_ANON_KEY
        ) {
            // Install Auth module with Android configuration
            install(Auth) {
                // Browser launcher for OAuth
                browserLauncher = BrowserLauncher { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    appContext.startActivity(intent)
                }

                // Enable session persistence
                persistSession = true

                // Use DataStore for session storage
                sessionStorage = DataStoreSessionStorage(appContext)
            }

            // Install Database module for todo operations
            install(Database)
        }
    }

    /**
     * Check if the client is initialized
     */
    val isInitialized: Boolean
        get() = _client != null
}

/**
 * SessionStorage implementation using Jetpack DataStore
 */
private class DataStoreSessionStorage(private val context: Context) : SessionStorage {

    companion object {
        private val SESSION_KEY = stringPreferencesKey("session")
        private val USER_KEY = stringPreferencesKey("user")
    }

    override suspend fun save(key: String, value: String) {
        val prefKey = when (key) {
            "insforge_session" -> SESSION_KEY
            "insforge_user" -> USER_KEY
            else -> stringPreferencesKey(key)
        }
        context.authDataStore.edit { prefs ->
            prefs[prefKey] = value
        }
    }

    override suspend fun get(key: String): String? {
        val prefKey = when (key) {
            "insforge_session" -> SESSION_KEY
            "insforge_user" -> USER_KEY
            else -> stringPreferencesKey(key)
        }
        return context.authDataStore.data.map { prefs ->
            prefs[prefKey]
        }.first()
    }

    override suspend fun remove(key: String) {
        val prefKey = when (key) {
            "insforge_session" -> SESSION_KEY
            "insforge_user" -> USER_KEY
            else -> stringPreferencesKey(key)
        }
        context.authDataStore.edit { prefs ->
            prefs.remove(prefKey)
        }
    }
}
