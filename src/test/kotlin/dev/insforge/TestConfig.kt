package dev.insforge

import dev.insforge.ai.AI
import dev.insforge.auth.Auth
import dev.insforge.database.Database
import dev.insforge.functions.Functions
import dev.insforge.realtime.Realtime
import dev.insforge.storage.Storage
import dev.insforge.auth.auth
import dev.insforge.logging.InsforgeLogLevel

/**
 * Test configuration for Insforge SDK integration tests.
 *
 * Values are read from environment variables when available (CI),
 * falling back to hardcoded defaults for local development.
 *
 * Environment variables:
 *  - INSFORGE_BASE_URL
 *  - INSFORGE_ANON_KEY
 *  - INSFORGE_TEST_EMAIL
 *  - INSFORGE_TEST_PASSWORD
 */
object TestConfig {
    val BASE_URL: String = System.getenv("INSFORGE_BASE_URL")
        ?: "https://pg6afqz9.us-east.insforge.app"

    val ANON_KEY: String = System.getenv("INSFORGE_ANON_KEY")
        ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3OC0xMjM0LTU2NzgtOTBhYi1jZGVmMTIzNDU2NzgiLCJlbWFpbCI6ImFub25AaW5zZm9yZ2UuY29tIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc5MDc5MzJ9.K0semVtcacV55qeEhVUI3WKWzT7p87JU7wNzdXysRWo"

    val TEST_EMAIL: String = System.getenv("INSFORGE_TEST_EMAIL")
        ?: "ci-test@insforge.dev"

    val TEST_PASSWORD: String = System.getenv("INSFORGE_TEST_PASSWORD")
        ?: "CiTest123456!"

    private var cachedAccessToken: String? = null
    private var cachedUserId: String? = null

    /**
     * Sign in with test credentials and cache the access token and user ID.
     * Attempts sign-up first (no-op if the user already exists).
     */
    private suspend fun ensureSignedIn() {
        if (cachedAccessToken != null) return

        val authClient = createAuthClient()
        try {
            try {
                authClient.auth.signUp(email = TEST_EMAIL, password = TEST_PASSWORD, name = "CI Test User")
            } catch (_: Exception) { }

            val response = authClient.auth.signIn(email = TEST_EMAIL, password = TEST_PASSWORD)
            cachedAccessToken = response.accessToken
            cachedUserId = response.user.id
        } finally {
            authClient.close()
        }
    }

    suspend fun getAccessToken(): String {
        ensureSignedIn()
        return cachedAccessToken!!
    }

    suspend fun getUserId(): String {
        ensureSignedIn()
        return cachedUserId!!
    }

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
            logLevel = InsforgeLogLevel.INFO
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

    /**
     * Create a test client with a dynamically obtained JWT for authenticated realtime testing.
     * Signs in via auth#signIn to get a fresh token instead of relying on a static JWT.
     */
    suspend fun createAuthenticatedRealtimeClient(): InsforgeClient {
        val token = getAccessToken()
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            accessToken = { token }
            install(Database)
            install(Realtime) {
                debug = true
            }
        }
    }

    /**
     * Create a test client with a dynamically obtained JWT and debug logging for realtime testing.
     */
    suspend fun createAuthenticatedRealtimeClient(debug: Boolean): InsforgeClient {
        val token = getAccessToken()
        return createInsforgeClient(
            baseURL = BASE_URL,
            anonKey = ANON_KEY
        ) {
            accessToken = { token }
            install(Database)
            install(Realtime) {
                this.debug = debug
            }
        }
    }
}
