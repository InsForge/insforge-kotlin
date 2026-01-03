package io.insforge.auth

import io.insforge.InsforgeClient
import io.insforge.InsforgeClientBuilder
import io.insforge.auth.models.*
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.InsforgePluginProvider
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Authentication module for Insforge
 *
 * Install this module in your Insforge client:
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(Auth) {
 *         // Optional configuration
 *     }
 * }
 *
 * // Access auth module
 * client.auth.signUp(email, password)
 * client.auth.signIn(email, password)
 * ```
 */
class Auth internal constructor(
    private val client: InsforgeClient,
    private val config: AuthConfig
) : InsforgePlugin<AuthConfig> {

    override val key: String = Auth.key

    private val _currentUser = MutableStateFlow<User?>(null)
    private val _currentSession = MutableStateFlow<Session?>(null)

    /**
     * Current authenticated user (reactive)
     */
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    /**
     * Current session with access token (reactive)
     */
    val currentSession: StateFlow<Session?> = _currentSession.asStateFlow()

    private val baseUrl = "${client.baseURL}/api/auth"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object : InsforgePluginProvider<AuthConfig, Auth> {
        override val key: String = "auth"
        private const val SESSION_KEY = "insforge_session"
        private const val USER_KEY = "insforge_user"

        override fun createConfig(configure: AuthConfig.() -> Unit): AuthConfig {
            return AuthConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: AuthConfig): Auth {
            return Auth(client, config).also { auth ->
                // Restore session from storage if persistence is enabled
                if (config.persistSession && config.sessionStorage != null) {
                    auth.restoreSession()
                }
            }
        }
    }

    /**
     * Restore session from persistent storage
     */
    private fun restoreSession() {
        scope.launch {
            try {
                val storage = config.sessionStorage ?: return@launch
                val sessionJson = storage.get(SESSION_KEY) ?: return@launch
                val userJson = storage.get(USER_KEY) ?: return@launch

                val accessToken = sessionJson
                val user = json.decodeFromString<User>(userJson)

                _currentSession.value = Session(user, accessToken)
                _currentUser.value = user
            } catch (e: Exception) {
                // Ignore restore errors - session will just be null
            }
        }
    }

    /**
     * Save session to persistent storage
     */
    private suspend fun saveSession(user: User, accessToken: String) {
        if (!config.persistSession) return
        val storage = config.sessionStorage ?: return

        storage.save(SESSION_KEY, accessToken)
        storage.save(USER_KEY, json.encodeToString(user))
    }

    /**
     * Clear session from persistent storage
     */
    private suspend fun clearPersistedSession() {
        if (!config.persistSession) return
        val storage = config.sessionStorage ?: return

        storage.remove(SESSION_KEY)
        storage.remove(USER_KEY)
    }

    // ============ Sign Up / Sign In ============

    /**
     * Register a new user
     *
     * @param email User email
     * @param password User password
     * @param name Optional user name
     * @return SignUpResponse with user and access token (if email verification not required)
     */
    suspend fun signUp(
        email: String,
        password: String,
        name: String? = null
    ): SignUpResponse {
        val response = client.httpClient.post("$baseUrl/users") {
            contentType(ContentType.Application.Json)
            setBody(SignUpRequest(email, password, name))
        }

        return handleAuthResponse<SignUpResponse>(response).also { result ->
            result.accessToken?.let { token ->
                _currentSession.value = Session(result.user, token)
                _currentUser.value = result.user
            }
        }
    }

    /**
     * Sign in with email and password
     *
     * @param email User email
     * @param password User password
     * @return SignInResponse with user and access token
     */
    suspend fun signIn(
        email: String,
        password: String
    ): SignInResponse {
        val response = client.httpClient.post("$baseUrl/sessions") {
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(email, password))
        }

        return handleAuthResponse<SignInResponse>(response).also { result ->
            _currentSession.value = Session(result.user, result.accessToken)
            _currentUser.value = result.user
        }
    }

    /**
     * Sign out the current user
     */
    suspend fun signOut() {
        _currentSession.value = null
        _currentUser.value = null
        clearPersistedSession()
    }

    // ============ Email Verification ============

    /**
     * Send verification email
     *
     * @param email Email to verify
     */
    suspend fun sendVerificationEmail(email: String) {
        val response = client.httpClient.post("$baseUrl/email/send-verification") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email))
        }

        handleAuthResponse<Unit>(response)
    }

    /**
     * Verify email with OTP code or magic link token
     *
     * @param otp 6-digit code or 64-character token
     * @param email Required for code verification, omit for link verification
     */
    suspend fun verifyEmail(otp: String, email: String? = null): VerifyEmailResponse {
        val response = client.httpClient.post("$baseUrl/email/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailRequest(email, otp))
        }

        return handleAuthResponse<VerifyEmailResponse>(response).also { result ->
            _currentSession.value = Session(result.user, result.accessToken)
            _currentUser.value = result.user
        }
    }

    // ============ Password Reset ============

    /**
     * Send password reset email
     *
     * @param email Email to send reset link/code
     */
    suspend fun sendPasswordReset(email: String) {
        val response = client.httpClient.post("$baseUrl/email/send-reset-password") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email))
        }

        handleAuthResponse<Unit>(response)
    }

    /**
     * Exchange reset password code for reset token (for code-based flow)
     *
     * @param email User email
     * @param code 6-digit code from email
     * @return ResetTokenResponse with token
     */
    suspend fun exchangeResetPasswordToken(email: String, code: String): ResetTokenResponse {
        val response = client.httpClient.post("$baseUrl/email/exchange-reset-password-token") {
            contentType(ContentType.Application.Json)
            setBody(ExchangeResetTokenRequest(email, code))
        }

        return handleAuthResponse(response)
    }

    /**
     * Reset password with OTP token
     *
     * @param newPassword New password
     * @param otp Reset token (from magic link or exchange endpoint)
     */
    suspend fun resetPassword(newPassword: String, otp: String) {
        val response = client.httpClient.post("$baseUrl/email/reset-password") {
            contentType(ContentType.Application.Json)
            setBody(ResetPasswordRequest(newPassword, otp))
        }

        handleAuthResponse<Unit>(response)
    }

    // ============ OAuth ============

    /**
     * Get OAuth authorization URL
     *
     * @param provider OAuth provider (google, github, etc.)
     * @param redirectUri URL to redirect after authentication
     * @return Authorization URL to redirect user to
     */
    suspend fun getOAuthUrl(provider: String, redirectUri: String): String {
        val response = client.httpClient.get("$baseUrl/oauth/$provider") {
            parameter("redirect_uri", redirectUri)
        }

        val result = handleAuthResponse<OAuthUrlResponse>(response)
        return result.authUrl
    }

    /**
     * Open InsForge's hosted authentication page in the system browser.
     *
     * This page supports both OAuth providers (Google, GitHub, Discord, etc.)
     * and email+password authentication.
     *
     * Flow:
     * 1. App calls signInWithDefaultPage(redirectTo:)
     * 2. SDK automatically opens the authentication URL in system browser
     * 3. User authenticates (OAuth or email+password)
     * 4. InsForge redirects to callback URL with parameters
     * 5. Android intercepts callback URL (via Custom URL Scheme or App Links)
     * 6. App calls handleAuthCallback(url)
     * 7. SDK creates session, updates auth state, and persists token
     *
     * @param redirectTo Callback URL where InsForge will redirect after authentication.
     *                   Can be a custom URL scheme (e.g., "yourapp://auth/callback")
     *                   or an App Link (e.g., "https://yourdomain.com/auth/callback")
     * @return The authentication URL (also opens in browser if browserLauncher is configured)
     * @throws IllegalStateException if browserLauncher is not configured
     *
     * Setup (Android):
     * ```kotlin
     * val client = createInsforgeClient(baseURL, anonKey) {
     *     install(Auth) {
     *         browserLauncher = BrowserLauncher { url ->
     *             val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
     *             context.startActivity(intent)
     *         }
     *         persistSession = true
     *         sessionStorage = object : SessionStorage {
     *             private val prefs = context.getSharedPreferences("insforge", Context.MODE_PRIVATE)
     *             override suspend fun save(key: String, value: String) {
     *                 prefs.edit().putString(key, value).apply()
     *             }
     *             override suspend fun get(key: String): String? = prefs.getString(key, null)
     *             override suspend fun remove(key: String) {
     *                 prefs.edit().remove(key).apply()
     *             }
     *         }
     *     }
     * }
     *
     * // Start OAuth flow - browser opens automatically
     * client.auth.signInWithDefaultPage("yourapp://auth/callback")
     * ```
     */
    fun signInWithDefaultPage(redirectTo: String): String {
        val encodedRedirect = java.net.URLEncoder.encode(redirectTo, "UTF-8")
        val authUrl = "${client.baseURL}/auth/sign-in?redirect=$encodedRedirect"

        // Automatically open browser if launcher is configured
        val launcher = config.browserLauncher
            ?: throw IllegalStateException(
                "browserLauncher is not configured. Please configure it when installing the Auth module:\n" +
                "install(Auth) {\n" +
                "    browserLauncher = BrowserLauncher { url ->\n" +
                "        // Open URL in system browser\n" +
                "    }\n" +
                "}"
            )
        launcher.launch(authUrl)

        return authUrl
    }

    /**
     * Get the authentication URL without opening the browser.
     *
     * Use this if you want to control when/how to open the browser yourself.
     *
     * @param redirectTo Callback URL where InsForge will redirect after authentication.
     * @return The authentication URL
     */
    fun getAuthUrl(redirectTo: String): String {
        val encodedRedirect = java.net.URLEncoder.encode(redirectTo, "UTF-8")
        return "${client.baseURL}/auth/sign-in?redirect=$encodedRedirect"
    }

    /**
     * Handle the callback URL from OAuth/authentication flow.
     *
     * This method parses the callback URL parameters, creates a session,
     * and persists the token if session persistence is enabled.
     * Call this when your app intercepts the redirect URL.
     *
     * Callback URL parameters:
     * - access_token: JWT access token for API requests
     * - user_id: User's unique ID
     * - email: User's email address
     * - name: User's display name (optional)
     * - csrf_token: CSRF protection token (optional)
     *
     * @param callbackUrl The full callback URL intercepted by the app
     * @return OAuthCallbackResult containing user information and access token
     * @throws IllegalArgumentException if required parameters are missing
     *
     * Example (Android):
     * ```kotlin
     * // In your Activity that handles the callback
     * override fun onNewIntent(intent: Intent?) {
     *     super.onNewIntent(intent)
     *     intent?.data?.let { uri ->
     *         lifecycleScope.launch {
     *             try {
     *                 val result = client.auth.handleAuthCallback(uri.toString())
     *                 // User is now authenticated
     *                 println("Authenticated: ${result.email}")
     *             } catch (e: Exception) {
     *                 // Handle error
     *             }
     *         }
     *     }
     * }
     * ```
     */
    suspend fun handleAuthCallback(callbackUrl: String): OAuthCallbackResult {
        val uri = java.net.URI(callbackUrl)
        val queryParams = parseQueryParams(uri.query ?: uri.fragment ?: "")

        val accessToken = queryParams["access_token"]
            ?: throw IllegalArgumentException("Missing access_token in callback URL")
        val userId = queryParams["user_id"]
            ?: throw IllegalArgumentException("Missing user_id in callback URL")
        val email = queryParams["email"]
            ?: throw IllegalArgumentException("Missing email in callback URL")
        val name = queryParams["name"]
        val csrfToken = queryParams["csrf_token"]

        val result = OAuthCallbackResult(
            accessToken = accessToken,
            userId = userId,
            email = email,
            name = name,
            csrfToken = csrfToken
        )

        // Create a minimal user object and update session state
        val user = User(
            id = userId,
            email = email,
            metadata = name?.let { mapOf("name" to it) },
            emailVerified = true,
            providers = null,
            createdAt = "",
            updatedAt = ""
        )
        _currentSession.value = Session(user, accessToken)
        _currentUser.value = user

        // Persist session if configured
        saveSession(user, accessToken)

        return result
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()

        return query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = decodeUrlComponent(parts[0])
                    val value = decodeUrlComponent(parts[1])
                    key to value
                } else null
            }
            .toMap()
    }

    /**
     * Decode URL component properly, handling %2B as literal + instead of space.
     * URLDecoder.decode treats + as space (per application/x-www-form-urlencoded),
     * but URL query params use %20 for space and %2B for literal +.
     */
    private fun decodeUrlComponent(value: String): String {
        // First, replace + with a placeholder to preserve literal + after %2B decoding
        // Then decode, then restore + symbols
        val preserved = value.replace("+", "%2B")
        return java.net.URLDecoder.decode(preserved, "UTF-8")
    }

    // ============ User Profile ============

    /**
     * Get current user from session token
     */
    suspend fun getCurrentUser(): CurrentUserResponse {
        val response = client.httpClient.get("$baseUrl/sessions/current")
        return handleAuthResponse(response)
    }

    /**
     * Update current user's profile
     *
     * @param profile Profile data (name, avatar_url, and custom fields)
     */
    suspend fun updateProfile(profile: Map<String, Any>): ProfileResponse {
        val response = client.httpClient.patch("$baseUrl/profiles/current") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("profile" to profile))
        }

        return handleAuthResponse(response)
    }

    /**
     * Get user profile by ID (public endpoint)
     *
     * @param userId User ID
     */
    suspend fun getProfile(userId: String): ProfileResponse {
        val response = client.httpClient.get("$baseUrl/profiles/$userId")
        return handleAuthResponse(response)
    }

    // ============ Admin Operations ============

    /**
     * List all users (admin only)
     *
     * @param offset Number of records to skip
     * @param limit Maximum number of records to return
     * @param search Search by email or name
     */
    suspend fun listUsers(
        offset: Int = 0,
        limit: Int = 10,
        search: String? = null
    ): ListUsersResponse {
        val response = client.httpClient.get("$baseUrl/users") {
            parameter("offset", offset)
            parameter("limit", limit)
            search?.let { parameter("search", it) }
        }

        return handleAuthResponse(response)
    }

    /**
     * Get specific user by ID (admin only)
     *
     * @param userId User ID
     */
    suspend fun getUser(userId: String): User {
        val response = client.httpClient.get("$baseUrl/users/$userId")
        return handleAuthResponse(response)
    }

    /**
     * Delete users (admin only)
     *
     * @param userIds List of user IDs to delete
     */
    suspend fun deleteUsers(userIds: List<String>): DeleteUsersResponse {
        val response = client.httpClient.delete("$baseUrl/users") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("userIds" to userIds))
        }

        return handleAuthResponse(response)
    }

    /**
     * Get public authentication configuration
     */
    suspend fun getPublicConfig(): AuthPublicConfig {
        val response = client.httpClient.get("$baseUrl/public-config")
        return handleAuthResponse(response)
    }

    // ============ Helper Methods ============

    private suspend inline fun <reified T> handleAuthResponse(response: HttpResponse): T {
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.Accepted, HttpStatusCode.NoContent -> {
                if (T::class == Unit::class) {
                    Unit as T
                } else {
                    response.body()
                }
            }
            else -> {
                val errorBody = response.bodyAsText()
                val error = try {
                    Json.decodeFromString<io.insforge.exceptions.ErrorResponse>(errorBody)
                } catch (e: Exception) {
                    throw InsforgeHttpException(
                        statusCode = response.status.value,
                        error = "UNKNOWN_ERROR",
                        message = errorBody.ifEmpty { response.status.description }
                    )
                }

                throw InsforgeHttpException(
                    statusCode = error.statusCode,
                    error = error.error,
                    message = error.message,
                    nextActions = error.nextActions
                )
            }
        }
    }

}

/**
 * Extension property for accessing Auth module
 */
val InsforgeClient.auth: Auth
    get() = plugin(Auth.key)
