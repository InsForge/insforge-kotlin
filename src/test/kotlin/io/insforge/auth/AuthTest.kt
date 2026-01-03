package io.insforge.auth

import io.insforge.TestConfig
import io.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for Auth module
 */
class AuthTest {

    private lateinit var client: io.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        client = TestConfig.createAuthClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Sign Up / Sign In Tests ============

    @Test
    fun `test signUp with new user`() = runTest {
        val email = "test_${System.currentTimeMillis()}@example.com"
        val password = "Test123456!"

        try {
            val response = client.auth.signUp(
                email = email,
                password = password,
                name = "Test User"
            )

            assertNotNull(response.user)
            assertEquals(email, response.user.email)
            println("SignUp successful: ${response.user.id}")
        } catch (e: InsforgeHttpException) {
            // Handle case where email verification is required
            println("SignUp response: ${e.message}")
        }
    }

    @Test
    fun `test signUp with invalid email format`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.auth.signUp(
                email = "invalid-email",
                password = "Test123456!"
            )
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test signUp with weak password`() = runTest {
        val email = "test_${System.currentTimeMillis()}@example.com"

        val exception = assertFailsWith<InsforgeHttpException> {
            client.auth.signUp(
                email = email,
                password = "123" // Too weak
            )
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test signIn with valid credentials`() = runTest {
        // First create a user, then sign in
        val email = "existing_user@example.com"
        val password = "Test123456!"

        try {
            val response = client.auth.signIn(
                email = email,
                password = password
            )

            assertNotNull(response.user)
            assertNotNull(response.accessToken)
            assertTrue(response.accessToken.isNotEmpty())
            println("SignIn successful: ${response.user.id}")
        } catch (e: InsforgeHttpException) {
            println("SignIn failed (expected if user doesn't exist): ${e.message}")
        }
    }

    @Test
    fun `test signIn with invalid credentials`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.auth.signIn(
                email = "nonexistent@example.com",
                password = "WrongPassword123!"
            )
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    @Test
    fun `test signOut clears session`() = runTest {
        // Sign out should clear the current session
        client.auth.signOut()

        assertNull(client.auth.currentUser.value)
        assertNull(client.auth.currentSession.value)
    }

    // ============ Email Verification Tests ============

    @Test
    fun `test sendVerificationEmail`() = runTest {
        val email = "verify_test@example.com"

        try {
            client.auth.sendVerificationEmail(email)
            println("Verification email sent to $email")
        } catch (e: InsforgeHttpException) {
            println("Send verification email response: ${e.message}")
        }
    }

    // ============ Password Reset Tests ============

    @Test
    fun `test sendPasswordReset`() = runTest {
        val email = "reset_test@example.com"

        try {
            client.auth.sendPasswordReset(email)
            println("Password reset email sent to $email")
        } catch (e: InsforgeHttpException) {
            println("Send password reset response: ${e.message}")
        }
    }

    // ============ Public Config Tests ============

    @Test
    fun `test getPublicConfig`() = runTest {
        try {
            val config = client.auth.getPublicConfig()
            println("Public config: $config")
        } catch (e: InsforgeHttpException) {
            println("Get public config failed: ${e.message}")
        }
    }

    // ============ Profile Tests ============

    @Test
    fun `test getCurrentUser without session`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.auth.getCurrentUser()
        }
        println("Expected error (no session): ${exception.error} - ${exception.message}")
    }

    // ============ OAuth Tests ============

    @Test
    fun `test getOAuthUrl for google`() = runTest {
        try {
            val authUrl = client.auth.getOAuthUrl(
                provider = "google",
                redirectUri = "https://example.com/callback"
            )
            assertTrue(authUrl.isNotEmpty())
            println("OAuth URL: $authUrl")
        } catch (e: InsforgeHttpException) {
            println("OAuth not configured: ${e.message}")
        }
    }

    @Test
    fun `test getAuthUrl generates correct URL`() {
        val redirectTo = "myapp://auth/callback"
        val authUrl = client.auth.getAuthUrl(redirectTo)

        assertTrue(authUrl.contains("/auth/sign-in"))
        assertTrue(authUrl.contains("redirect="))
        assertTrue(authUrl.contains(java.net.URLEncoder.encode(redirectTo, "UTF-8")))
        println("Auth URL: $authUrl")
    }

    @Test
    fun `test getAuthUrl with app link redirect`() {
        val redirectTo = "https://myapp.example.com/auth/callback"
        val authUrl = client.auth.getAuthUrl(redirectTo)

        assertTrue(authUrl.contains("/auth/sign-in"))
        assertTrue(authUrl.contains("redirect="))
        println("Auth URL with app link: $authUrl")
    }

    @Test
    fun `test signInWithDefaultPage throws when browserLauncher not configured`() {
        val exception = assertFailsWith<IllegalStateException> {
            client.auth.signInWithDefaultPage("myapp://auth/callback")
        }
        assertTrue(exception.message?.contains("browserLauncher") == true)
    }

    @Test
    fun `test handleAuthCallback parses callback URL correctly`() = runTest {
        val callbackUrl = "myapp://auth/callback?access_token=test_token_123&user_id=user_456&email=test@example.com&name=Test%20User"

        val result = client.auth.handleAuthCallback(callbackUrl)

        assertEquals("test_token_123", result.accessToken)
        assertEquals("user_456", result.userId)
        assertEquals("test@example.com", result.email)
        assertEquals("Test User", result.name)
        assertNull(result.csrfToken)

        // Verify session was updated
        assertNotNull(client.auth.currentSession.value)
        assertEquals("test_token_123", client.auth.currentSession.value?.accessToken)
        assertEquals("user_456", client.auth.currentUser.value?.id)
        assertEquals("test@example.com", client.auth.currentUser.value?.email)
    }

    @Test
    fun `test handleAuthCallback with csrf token`() = runTest {
        val callbackUrl = "https://myapp.example.com/auth/callback?access_token=token_abc&user_id=uid_123&email=user@test.com&name=Jane%20Doe&csrf_token=csrf_xyz"

        val result = client.auth.handleAuthCallback(callbackUrl)

        assertEquals("token_abc", result.accessToken)
        assertEquals("uid_123", result.userId)
        assertEquals("user@test.com", result.email)
        assertEquals("Jane Doe", result.name)
        assertEquals("csrf_xyz", result.csrfToken)
    }

    @Test
    fun `test handleAuthCallback without optional name`() = runTest {
        val callbackUrl = "myapp://auth/callback?access_token=token_123&user_id=user_abc&email=noname@example.com"

        val result = client.auth.handleAuthCallback(callbackUrl)

        assertEquals("token_123", result.accessToken)
        assertEquals("user_abc", result.userId)
        assertEquals("noname@example.com", result.email)
        assertNull(result.name)
    }

    @Test
    fun `test handleAuthCallback throws on missing access_token`() = runTest {
        val callbackUrl = "myapp://auth/callback?user_id=user_123&email=test@example.com"

        val exception = assertFailsWith<IllegalArgumentException> {
            client.auth.handleAuthCallback(callbackUrl)
        }
        assertTrue(exception.message?.contains("access_token") == true)
    }

    @Test
    fun `test handleAuthCallback throws on missing user_id`() = runTest {
        val callbackUrl = "myapp://auth/callback?access_token=token_123&email=test@example.com"

        val exception = assertFailsWith<IllegalArgumentException> {
            client.auth.handleAuthCallback(callbackUrl)
        }
        assertTrue(exception.message?.contains("user_id") == true)
    }

    @Test
    fun `test handleAuthCallback throws on missing email`() = runTest {
        val callbackUrl = "myapp://auth/callback?access_token=token_123&user_id=user_456"

        val exception = assertFailsWith<IllegalArgumentException> {
            client.auth.handleAuthCallback(callbackUrl)
        }
        assertTrue(exception.message?.contains("email") == true)
    }

    @Test
    fun `test handleAuthCallback with URL encoded values`() = runTest {
        // Email with special character, name with space
        val callbackUrl = "myapp://auth/callback?access_token=token_123&user_id=user_456&email=test%2Buser%40example.com&name=John%20Doe"

        val result = client.auth.handleAuthCallback(callbackUrl)

        assertEquals("test+user@example.com", result.email)
        assertEquals("John Doe", result.name)
    }

    // ============ State Flow Tests ============

    @Test
    fun `test currentUser state flow is initially null`() = runTest {
        assertNull(client.auth.currentUser.value)
    }

    @Test
    fun `test currentSession state flow is initially null`() = runTest {
        assertNull(client.auth.currentSession.value)
    }
}
