package dev.insforge.http

import dev.insforge.auth.Auth
import dev.insforge.auth.auth
import dev.insforge.createInsforgeClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Mutex + CompletableDeferred token refresh fix in [InsforgeHttpClient].
 *
 * Strategy: all HTTP interactions go through a single MockEngine per test.
 * Each test first simulates a sign-in (so Auth sets a real session with a refresh
 * token), then exercises the protected endpoint → 401 → refresh → retry flow.
 */
class InsforgeHttpClientTest {

    // ── JSON helpers ────────────────────────────────────────────────────────

    private val jsonContent = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val fakeUser = """
        {
          "id": "user-1",
          "email": "test@example.com",
          "emailVerified": true,
          "createdAt": "2024-01-01T00:00:00Z",
          "updatedAt": "2024-01-01T00:00:00Z"
        }
    """.trimIndent()

    private fun signInOk(accessToken: String, refreshToken: String) = """
        {
          "user": $fakeUser,
          "accessToken": "$accessToken",
          "refreshToken": "$refreshToken"
        }
    """.trimIndent()

    private fun refreshOk(newAccessToken: String, newRefreshToken: String) = """
        {
          "user": $fakeUser,
          "accessToken": "$newAccessToken",
          "refreshToken": "$newRefreshToken"
        }
    """.trimIndent()

    private fun unauthorizedBody() = """{"statusCode":401,"error":"UNAUTHORIZED","message":"Token expired"}"""

    // ── Test 1: single 401 → one refresh → retry succeeds ───────────────────

    @Test
    fun `single 401 triggers exactly one refresh and retries with new token`() = runTest {
        val refreshCount = AtomicInteger(0)
        val requestCount = AtomicInteger(0)

        val engine = MockEngine { request ->
            val n = requestCount.incrementAndGet()
            val path = request.url.encodedPath
            when {
                // 1st call: sign-in → 200
                path.contains("/api/auth/sessions") && n == 1 ->
                    respond(signInOk("old-token", "refresh-token"), HttpStatusCode.OK, jsonContent)

                // 2nd call: protected endpoint → 401
                path.contains("/api/data") && n == 2 ->
                    respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)

                // 3rd call: /api/auth/refresh → 200 (counts as one refresh)
                path.contains("/api/auth/refresh") -> {
                    refreshCount.incrementAndGet()
                    respond(refreshOk("new-token", "new-refresh"), HttpStatusCode.OK, jsonContent)
                }

                // 4th call: retry of protected endpoint → 200
                path.contains("/api/data") ->
                    respond("{}", HttpStatusCode.OK, jsonContent)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        // Establish a session (sets refreshToken so auto-refresh is eligible)
        client.auth.signIn("test@example.com", "password")

        val response = client.httpClient.get("https://test.insforge.app/api/data")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, refreshCount.get(), "Refresh should be called exactly once")
    }

    // ── Test 2: concurrent 401s → only ONE refresh call ─────────────────────

    @Test
    fun `concurrent 401 responses trigger only one token refresh`() = runTest {
        val refreshCount = AtomicInteger(0)
        val requestCount = AtomicInteger(0)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                // Sign-in
                path.contains("/api/auth/sessions") ->
                    respond(signInOk("old-token", "refresh-token"), HttpStatusCode.OK, jsonContent)

                // Refresh endpoint — add delay to keep it in-flight while others also try
                path.contains("/api/auth/refresh") -> {
                    delay(100)
                    refreshCount.incrementAndGet()
                    respond(refreshOk("new-token", "new-refresh"), HttpStatusCode.OK, jsonContent)
                }

                // Protected endpoint: first 5 calls → 401, subsequent (retries) → 200
                path.contains("/api/data") -> {
                    val n = requestCount.incrementAndGet()
                    if (n <= 5) {
                        respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
                    } else {
                        respond("{}", HttpStatusCode.OK, jsonContent)
                    }
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        client.auth.signIn("test@example.com", "password")

        // Fire 5 concurrent requests that all receive 401 simultaneously
        val results = (1..5).map {
            async { client.httpClient.get("https://test.insforge.app/api/data") }
        }.awaitAll()

        results.forEach { assertEquals(HttpStatusCode.OK, it.status) }
        assertEquals(1, refreshCount.get(), "Only one token refresh should occur for concurrent 401s")
    }

    // ── Test 3: refresh failure → all requests get 401, refresh called once ──

    @Test
    fun `when refresh fails all concurrent requests get 401 and refresh is called once`() = runTest {
        val refreshCount = AtomicInteger(0)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/api/auth/sessions") ->
                    respond(signInOk("old-token", "refresh-token"), HttpStatusCode.OK, jsonContent)

                path.contains("/api/auth/refresh") -> {
                    delay(50)
                    refreshCount.incrementAndGet()
                    respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
                }

                path.contains("/api/data") ->
                    respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        client.auth.signIn("test@example.com", "password")

        val results = (1..3).map {
            async { client.httpClient.get("https://test.insforge.app/api/data") }
        }.awaitAll()

        // All requests should propagate the 401 (refresh failed, no retry token)
        results.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }
        // Refresh was attempted exactly once despite 3 concurrent 401s
        assertEquals(1, refreshCount.get(), "Refresh should be attempted once even when it fails")
    }

    // ── Test 4: /api/auth/refresh endpoint itself never triggers auto-refresh ─

    @Test
    fun `401 on the refresh endpoint itself is NOT retried to avoid infinite loop`() = runTest {
        val refreshCount = AtomicInteger(0)
        val requestCount = AtomicInteger(0)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/api/auth/sessions") ->
                    respond(signInOk("old-token", "refresh-token"), HttpStatusCode.OK, jsonContent)

                // The refresh endpoint itself returns 401 — should not trigger another refresh
                path.contains("/api/auth/refresh") -> {
                    requestCount.incrementAndGet()
                    if (requestCount.get() == 1) {
                        refreshCount.incrementAndGet() // only the sign-in triggered this
                        respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
                    } else {
                        // If this is called more than once, the guard failed
                        respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
                    }
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        client.auth.signIn("test@example.com", "password")

        // Directly call the refresh endpoint — a 401 here must NOT trigger another refresh
        val response = client.httpClient.post("https://test.insforge.app/api/auth/refresh")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, requestCount.get(), "Refresh endpoint should not be called recursively")
    }

    // ── Test 5: sign-up endpoint 401 is NOT retried ──────────────────────────

    @Test
    fun `401 on sign-up endpoint is NOT retried`() = runTest {
        val signUpCallCount = AtomicInteger(0)
        val refreshCount = AtomicInteger(0)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val method = request.method
            when {
                // Initial sign-in to establish session (POST /api/auth/sessions)
                path.contains("/api/auth/sessions") ->
                    respond(signInOk("old-token", "refresh-token"), HttpStatusCode.OK, jsonContent)

                // Sign-up endpoint → 401
                path.contains("/api/auth/users") && method == HttpMethod.Post -> {
                    signUpCallCount.incrementAndGet()
                    respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
                }

                path.contains("/api/auth/refresh") -> {
                    refreshCount.incrementAndGet()
                    respond(refreshOk("new-token", "new-refresh"), HttpStatusCode.OK, jsonContent)
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        client.auth.signIn("test@example.com", "password")

        val response = client.httpClient.post("https://test.insforge.app/api/auth/users")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(1, signUpCallCount.get(), "Sign-up endpoint called only once, no retry")
        assertEquals(0, refreshCount.get(), "Refresh must NOT be triggered for sign-up 401")
    }

    // ── Test 6: no Auth plugin → 401 returned as-is without any refresh ──────

    @Test
    fun `401 without Auth plugin installed is returned as-is`() = runTest {
        val engine = MockEngine { _ ->
            respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
        }

        // No install(Auth)
        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
        }

        val response = client.httpClient.get("https://test.insforge.app/api/data")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ── Test 7: refreshDeferred resets so a second 401 burst gets its own refresh

    @Test
    fun `after first refresh completes a subsequent 401 triggers a fresh independent refresh`() = runTest {
        val refreshCount = AtomicInteger(0)
        val dataCallCount = AtomicInteger(0)

        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/api/auth/sessions") ->
                    respond(signInOk("old-token", "refresh-token"), HttpStatusCode.OK, jsonContent)

                path.contains("/api/auth/refresh") -> {
                    refreshCount.incrementAndGet()
                    respond(refreshOk("new-token-${refreshCount.get()}", "new-refresh"), HttpStatusCode.OK, jsonContent)
                }

                path.contains("/api/data") -> {
                    val n = dataCallCount.incrementAndGet()
                    // Calls 1 and 3 return 401; calls 2 and 4 (retries) return 200
                    if (n == 1 || n == 3) {
                        respond(unauthorizedBody(), HttpStatusCode.Unauthorized, jsonContent)
                    } else {
                        respond("{}", HttpStatusCode.OK, jsonContent)
                    }
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        client.auth.signIn("test@example.com", "password")

        // First request: 401 → refresh #1 → retry (200)
        val r1 = client.httpClient.get("https://test.insforge.app/api/data")
        assertEquals(HttpStatusCode.OK, r1.status)
        assertEquals(1, refreshCount.get())

        // Second request (separate burst): 401 → refresh #2 → retry (200)
        val r2 = client.httpClient.get("https://test.insforge.app/api/data")
        assertEquals(HttpStatusCode.OK, r2.status)
        assertEquals(2, refreshCount.get(), "A second independent 401 should trigger a second refresh")
    }
}
