package io.insforge

import io.insforge.ai.AI
import io.insforge.auth.Auth
import io.insforge.database.Database
import io.insforge.functions.Functions
import io.insforge.realtime.Realtime
import io.insforge.storage.Storage
import io.insforge.logging.InsforgeLogLevel

/**
 * Test configuration for Insforge SDK integration tests
 */
object TestConfig {
    const val BASE_URL = "https://pg6afqz9.us-east.insforge.app"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3OC0xMjM0LTU2NzgtOTBhYi1jZGVmMTIzNDU2NzgiLCJlbWFpbCI6ImFub25AaW5zZm9yZ2UuY29tIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc5MDc5MzJ9.K0semVtcacV55qeEhVUI3WKWzT7p87JU7wNzdXysRWo"

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
            logLevel = InsforgeLogLevel.VERBOSE
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
            // Enable full HTTP logging for debugging
            logLevel = InsforgeLogLevel.VERBOSE
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
            // Realtime needs Database for the todos tests
            install(Database)
            install(Realtime)
        }
    }

    // JWT token for authenticated user testing
    const val TEST_JWT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIwODVhNDgxZS05NGI4LTRiZjktYjNhMC03ZjBlNTBmN2EwNzIiLCJlbWFpbCI6Imp1bndlbi5mZW5nQGluc2ZvcmdlLmRldiIsInJvbGUiOiJhdXRoZW50aWNhdGVkIiwiaWF0IjoxNzY3NzQyMTE2LCJleHAiOjE3NjgzNDY5MTZ9.jhfprod2CU1Bn2j92wG9_j0MdmbtycpRI0SHoqqDtcc"

    /**
     * Create a test client with JWT token for authenticated realtime testing
     */
    fun createAuthenticatedRealtimeClient(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            // Use JWT token for authentication
            accessToken = { TEST_JWT_TOKEN }
            // Realtime needs Database for the todos tests
            install(Database)
            install(Realtime) {
                debug = true  // Enable WebSocket message logging
            }
        }
    }

    /**
     * Create a test client with JWT token and debug logging enabled for realtime testing
     */
    fun createAuthenticatedRealtimeClientWithDebug(): InsforgeClient {
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            // Use JWT token for authentication
            accessToken = { TEST_JWT_TOKEN }
            // Realtime needs Database for the todos tests
            install(Database)
            install(Realtime) {
                debug = true  // Enable WebSocket message logging
            }
        }
    }
}
