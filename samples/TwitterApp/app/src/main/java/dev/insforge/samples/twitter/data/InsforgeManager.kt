package dev.insforge.samples.twitter.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.insforge.ai.AI
import dev.insforge.auth.Auth
import dev.insforge.auth.BrowserLauncher
import dev.insforge.auth.SessionStorage
import dev.insforge.createInsforgeClient
import dev.insforge.database.Database
import dev.insforge.functions.Functions
import dev.insforge.logging.InsforgeLogLevel
import dev.insforge.realtime.Realtime
import dev.insforge.storage.Storage
import dev.insforge.samples.twitter.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "insforge_auth")

class DataStoreSessionStorage(private val context: Context) : SessionStorage {
    override suspend fun save(key: String, value: String) {
        context.authDataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = value
        }
    }

    override suspend fun get(key: String): String? {
        return context.authDataStore.data.map { prefs ->
            prefs[stringPreferencesKey(key)]
        }.first()
    }

    override suspend fun remove(key: String) {
        context.authDataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
        }
    }
}

class InsforgeManager(private val context: Context) {

    val client = createInsforgeClient(
        baseURL = BuildConfig.INSFORGE_URL,
        anonKey = BuildConfig.INSFORGE_ANON_KEY
    ) {
        // DEBUG: logs request method/URL and response status
        // VERBOSE: logs full headers and request/response bodies
        logLevel = InsforgeLogLevel.VERBOSE

        install(Auth) {
            // Configure BrowserLauncher for OAuth
            browserLauncher = BrowserLauncher { url ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            // Enable session persistence
            persistSession = true

            // Use DataStore for session storage
            sessionStorage = DataStoreSessionStorage(context)
        }

        // Install Database module
        install(Database)

        // Install Storage module
        install(Storage)

        // Install Realtime module for real-time subscriptions
        install(Realtime) {
            autoReconnect = true
            reconnectDelay = 5000
            debug = true
        }

        // Install Functions module
        install(Functions)

        // Install AI module
        install(AI)
    }

    companion object {
        @Volatile
        private var instance: InsforgeManager? = null

        fun getInstance(context: Context): InsforgeManager {
            return instance ?: synchronized(this) {
                instance ?: InsforgeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}