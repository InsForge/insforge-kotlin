package dev.insforge.auth

import dev.insforge.createInsforgeClient
import dev.insforge.exceptions.InsforgeHttpException
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the passwordless email OTP sign-in flow
 * ([Auth.signInWithOtp] and [Auth.verifyOtp]) using a MockEngine.
 */
class AuthOtpTest {

    private val jsonContent = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val fakeUser = """
        {
          "id": "user-1",
          "email": "user@example.com",
          "emailVerified": true,
          "createdAt": "2024-01-01T00:00:00Z",
          "updatedAt": "2024-01-01T00:00:00Z"
        }
    """.trimIndent()

    private fun sessionOk(accessToken: String, refreshToken: String) = """
        {
          "user": $fakeUser,
          "accessToken": "$accessToken",
          "refreshToken": "$refreshToken"
        }
    """.trimIndent()

    // ── signInWithOtp ────────────────────────────────────────────────────────

    @Test
    fun `signInWithOtp posts the email to the send-otp endpoint and returns the generic payload`() = runTest {
        var requestUrl: Url? = null
        var requestBody: String? = null

        val engine = MockEngine { request ->
            requestUrl = request.url
            requestBody = String(request.body.toByteArray())
            respond(
                """{"success":true,"message":"If sign-in is available for this email, we have sent a verification code."}""",
                HttpStatusCode.Accepted,
                jsonContent
            )
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        val response = client.auth.signInWithOtp("user@example.com")

        assertTrue(response.success)
        assertTrue(response.message.isNotEmpty())

        assertEquals("/api/auth/email/send-otp", requestUrl?.encodedPath)
        val body = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("user@example.com", body["email"]?.jsonPrimitive?.content)
    }

    // ── verifyOtp ────────────────────────────────────────────────────────────

    @Test
    fun `verifyOtp posts method otp to the sessions endpoint and persists the session`() = runTest {
        var requestUrl: Url? = null
        var requestBody: String? = null

        val engine = MockEngine { request ->
            requestUrl = request.url
            requestBody = String(request.body.toByteArray())
            respond(sessionOk("access-token", "refresh-token"), HttpStatusCode.OK, jsonContent)
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        val response = client.auth.verifyOtp(
            email = "user@example.com",
            otp = "123456",
            name = "Ada Lovelace"
        )

        assertEquals("access-token", response.accessToken)
        assertEquals("user@example.com", response.user.email)

        // The session must be persisted — this is what distinguishes verifyOtp from signInWithOtp.
        assertNotNull(client.auth.currentSession.value)
        assertEquals("access-token", client.auth.currentSession.value?.accessToken)
        assertEquals("refresh-token", client.auth.currentSession.value?.refreshToken)
        assertEquals("user-1", client.auth.currentUser.value?.id)

        assertEquals("/api/auth/sessions", requestUrl?.encodedPath)
        assertEquals("mobile", requestUrl?.parameters?.get("client_type"))

        val body = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("otp", body["method"]?.jsonPrimitive?.content)
        assertEquals("user@example.com", body["email"]?.jsonPrimitive?.content)
        assertEquals("123456", body["otp"]?.jsonPrimitive?.content)
        assertEquals("Ada Lovelace", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `verifyOtp omits name when not provided`() = runTest {
        var requestBody: String? = null

        val engine = MockEngine { request ->
            requestBody = String(request.body.toByteArray())
            respond(sessionOk("access-token", "refresh-token"), HttpStatusCode.OK, jsonContent)
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        client.auth.verifyOtp(email = "user@example.com", otp = "123456")

        val body = Json.parseToJsonElement(requestBody!!).jsonObject
        assertEquals("otp", body["method"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("name"), "name should be omitted when not provided")
    }

    @Test
    fun `verifyOtp surfaces API errors and does not persist a session`() = runTest {
        val engine = MockEngine {
            respond(
                """{"statusCode":401,"error":"INVALID_OTP","message":"Invalid or expired code"}""",
                HttpStatusCode.Unauthorized,
                jsonContent
            )
        }

        val client = createInsforgeClient("https://test.insforge.app", "anon-key") {
            httpEngine = engine
            install(Auth)
        }

        val exception = assertFailsWith<InsforgeHttpException> {
            client.auth.verifyOtp(email = "user@example.com", otp = "000000")
        }

        assertEquals(401, exception.statusCode)
        assertNull(client.auth.currentSession.value)
        assertNull(client.auth.currentUser.value)
    }
}
