package io.insforge

import io.insforge.ai.AI
import io.insforge.auth.Auth
import io.insforge.database.Database
import io.insforge.functions.Functions
import io.insforge.realtime.Realtime
import io.insforge.storage.Storage
import io.ktor.client.plugins.logging.LogLevel

/**
 * Test configuration for Insforge SDK integration tests
 */
object TestConfig {
    const val BASE_URL = "https://pg6afqz9.us-east.insforge.app"
    const val ANON_KEY = "ik_ca177fcf1e2e72e8d1e0c2c23dbe3b79"

    /**
     * Create a fully configured test client with all plugins installed
     */
    fun createTestClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            install(Auth)
            install(Database)
            install(Storage)
            install(Functions)
            install(AI)
            install(Realtime)
        }
    }

    /**
     * Create a test client with only Auth plugin
     */
    fun createAuthClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            install(Auth)
        }
    }

    /**
     * Create a test client with only Database plugin
     */
    fun createDatabaseClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            // Enable full HTTP logging for debugging
            defaultLogLevel = LogLevel.ALL
            install(Database)
        }
    }

    /**
     * Create a test client with only Storage plugin
     */
    fun createStorageClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            install(Storage)
        }
    }

    /**
     * Create a test client with only Functions plugin
     */
    fun createFunctionsClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            install(Functions)
        }
    }

    /**
     * Create a test client with only AI plugin
     */
    fun createAIClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            install(AI)
        }
    }

    /**
     * Create a test client with only Realtime plugin
     */
    fun createRealtimeClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            install(Realtime)
        }
    }
}
