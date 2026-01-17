package dev.insforge.auth

import dev.insforge.InsforgeClient
import dev.insforge.InsforgeClientBuilder
import dev.insforge.auth.models.*
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.plugins.InsforgePlugin
import dev.insforge.plugins.InsforgePluginProvider
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

// Re-export OAuthProvider from models for convenience
typealias OAuthProvider = dev.insforge.auth.models.OAuthProvider

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
 * client.auth.signOut()
 * client.auth.signInWithDefaultPage(redirectTo)
 * ```
 */
class Auth internal constructor(
    private val client: InsforgeClient,
    private val config: AuthConfig
) : InsforgePlugin<AuthConfig> {

    override val key: String = Auth.key

    private val _currentUser = MutableStateFlow<User?>(null)
    private val _currentSession = MutableStateFlow<Session?>(null)

    // PKCE code verifier for OAuth flow (stored temporarily until exchange)
    private var pendingPkceVerifier: String? = null

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
        private const val REFRESH_TOKEN_KEY = "insforge_refresh_token"
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
     * Restore session from persistent storage.
     *
     * Only refreshToken and user are persisted. On restore, we automatically
     * refresh to get a new accessToken.
     */
    private fun restoreSession() {
        scope.launch {
            try {
                val storage = config.sessionStorage ?: return@launch
                val refreshToken = storage.get(REFRESH_TOKEN_KEY) ?: return@launch
                val userJson = storage.get(USER_KEY) ?: return@launch

                val user = json.decodeFromString<User>(userJson)
                _currentUser.value = user

                // Try to refresh the access token
                try {
                    val result = refreshAccessTokenInternal(refreshToken)
                    _currentSession.value = Session(result.user, result.accessToken, result.refreshToken)
                    _currentUser.value = result.user

                    // Update stored refresh token if a new one was returned
                    result.refreshToken?.let { newRefreshToken ->
                        storage.save(REFRESH_TOKEN_KEY, newRefreshToken)
                    }
                    storage.save(USER_KEY, json.encodeToString(result.user))
                } catch (e: Exception) {
                    // Refresh failed, clear stored session
                    clearPersistedSession()
                }
            } catch (e: Exception) {
                // Ignore restore errors - session will just be null
            }
        }
    }

    /**
     * Save session to persistent storage.
     *
     * Only refreshToken and user are persisted. accessToken is kept in memory only.
     */
    private suspend fun saveSession(user: User, accessToken: String, refreshToken: String?) {
        // Always update in-memory session
        _currentSession.value = Session(user, accessToken, refreshToken)
        _currentUser.value = user

        // Persist refreshToken and user if configured
        if (!config.persistSession) return
        val storage = config.sessionStorage ?: return

        refreshToken?.let { storage.save(REFRESH_TOKEN_KEY, it) }
        storage.save(USER_KEY, json.encodeToString(user))
    }

    /**
     * Clear session from persistent storage
     */
    private suspend fun clearPersistedSession() {
        if (!config.persistSession) return
        val storage = config.sessionStorage ?: return

        storage.remove(REFRESH_TOKEN_KEY)
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
            parameter("client_type", config.clientType.value)
            contentType(ContentType.Application.Json)
            setBody(SignUpRequest(email, password, name))
        }

        return handleAuthResponse<SignUpResponse>(response).also { result ->
            result.user?.let { user ->
                result.accessToken?.let { token ->
                    saveSession(user, token, result.refreshToken)
                }
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
            parameter("client_type", config.clientType.value)
            contentType(ContentType.Application.Json)
            setBody(SignInRequest(email, password))
        }

        return handleAuthResponse<SignInResponse>(response).also { result ->
            saveSession(result.user, result.accessToken, result.refreshToken)
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
            parameter("client_type", config.clientType.value)
            contentType(ContentType.Application.Json)
            setBody(VerifyEmailRequest(email, otp))
        }

        return handleAuthResponse<VerifyEmailResponse>(response).also { result ->
            saveSession(result.user, result.accessToken, result.refreshToken)
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

    // ============ Token Refresh ============

    /**
     * Refresh the access token using the stored refresh token.
     *
     * This is called automatically when:
     * - The session is restored from storage
     * - An API call returns 401 Unauthorized (auto-retry)
     *
     * You can also call this manually to proactively refresh the token.
     *
     * @return RefreshTokenResponse with new access token and optionally new refresh token
     * @throws IllegalStateException if no refresh token is available
     */
    suspend fun refreshAccessToken(): RefreshTokenResponse {
        val refreshToken = _currentSession.value?.refreshToken
            ?: config.sessionStorage?.get(REFRESH_TOKEN_KEY)
            ?: throw IllegalStateException("No refresh token available")

        return refreshAccessTokenInternal(refreshToken).also { result ->
            saveSession(result.user, result.accessToken, result.refreshToken ?: refreshToken)
        }
    }

    /**
     * Internal method to refresh access token.
     * Does not update session state - caller is responsible for that.
     */
    private suspend fun refreshAccessTokenInternal(refreshToken: String): RefreshTokenResponse {
        val response = client.httpClient.post("$baseUrl/refresh") {
            parameter("client_type", config.clientType.value)
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenRequest(refreshToken))
        }

        return handleAuthResponse(response)
    }

    // ============ OAuth ============

    /**
     * Get OAuth authorization URL with PKCE challenge.
     *
     * @param provider OAuth provider
     * @param redirectUri URL to redirect after authentication
     * @param codeChallenge PKCE code challenge (SHA-256 hash of code verifier)
     * @return Authorization URL to redirect user to
     */
    suspend fun getOAuthUrl(
        provider: OAuthProvider,
        redirectUri: String,
        codeChallenge: String
    ): String {
        val response = client.httpClient.get("$baseUrl/oauth/${provider.value}") {
            parameter("redirect_uri", redirectUri)
            parameter("code_challenge", codeChallenge)
            parameter("code_challenge_method", "S256")
        }

        val result = handleAuthResponse<OAuthUrlResponse>(response)
        return result.authUrl
    }

    /**
     * Sign in with a specific OAuth provider using PKCE for security.
     *
     * Opens the OAuth provider's authentication page in the system browser.
     * After successful authentication, the user will be redirected to your callback URL
     * with an exchange code that must be exchanged for tokens using handleAuthCallback().
     *
     * Flow:
     * 1. App calls signInWithOAuthPage(provider, redirectUri)
     * 2. SDK generates PKCE code_verifier and code_challenge
     * 3. SDK fetches the OAuth authorization URL from InsForge (with code_challenge)
     * 4. SDK automatically opens the OAuth URL in system browser
     * 5. User authenticates with the provider (Google, GitHub, etc.)
     * 6. Provider redirects to InsForge, then InsForge redirects to your callback URL with exchange_code
     * 7. Android intercepts callback URL (via Custom URL Scheme or App Links)
     * 8. App calls handleAuthCallback(url)
     * 9. SDK exchanges the code using code_verifier and receives tokens
     * 10. SDK creates session, updates auth state, and persists refresh token
     *
     * @param provider OAuth provider (e.g., OAuthProvider.GOOGLE, OAuthProvider.GITHUB)
     * @param redirectUri Callback URL where InsForge will redirect after authentication.
     *                    Can be a custom URL scheme (e.g., "yourapp://auth/callback")
     *                    or an App Link (e.g., "https://yourdomain.com/auth/callback")
     * @return The OAuth authorization URL (also opens in browser if browserLauncher is configured)
     * @throws IllegalStateException if browserLauncher is not configured
     *
     * Example (Android):
     * ```kotlin
     * // Configure browserLauncher when creating the client
     * val client = createInsforgeClient(baseURL, anonKey) {
     *     install(Auth) {
     *         browserLauncher = BrowserLauncher { url ->
     *             val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
     *             context.startActivity(intent)
     *         }
     *         persistSession = true
     *         sessionStorage = mySessionStorage
     *         clientType = ClientType.MOBILE
     *     }
     * }
     *
     * // Start OAuth flow with Google
     * lifecycleScope.launch {
     *     val authUrl = client.auth.signInWithOAuthPage(OAuthProvider.GOOGLE, "yourapp://auth/callback")
     *     // Browser opens automatically
     * }
     *
     * // Handle callback in your Activity
     * override fun onNewIntent(intent: Intent?) {
     *     super.onNewIntent(intent)
     *     intent?.data?.let { uri ->
     *         lifecycleScope.launch {
     *             val result = client.auth.handleAuthCallback(uri.toString())
     *             // User is now authenticated
     *         }
     *     }
     * }
     * ```
     */
    suspend fun signInWithOAuthPage(provider: OAuthProvider, redirectUri: String): String {
        val launcher = config.browserLauncher
            ?: throw IllegalStateException(
                "browserLauncher is not configured. Please configure it when installing the Auth module:\n" +
                "install(Auth) {\n" +
                "    browserLauncher = BrowserLauncher { url ->\n" +
                "        // Open URL in system browser\n" +
                "    }\n" +
                "}"
            )

        // Generate PKCE pair and store verifier for later exchange
        val pkce = PKCE.generate()
        pendingPkceVerifier = pkce.codeVerifier

        val authUrl = getOAuthUrl(provider, redirectUri, pkce.codeChallenge)
        launcher.launch(authUrl)

        return authUrl
    }

    /**
     * Open InsForge's hosted authentication page in the system browser.
     *
     * This page supports both OAuth providers (Google, GitHub, Discord, etc.)
     * and email+password authentication. Uses PKCE for security.
     *
     * Flow:
     * 1. App calls signInWithDefaultPage(redirectTo:)
     * 2. SDK generates PKCE code_verifier and code_challenge
     * 3. SDK automatically opens the authentication URL in system browser (with code_challenge)
     * 4. User authenticates (OAuth or email+password)
     * 5. InsForge redirects to callback URL with exchange_code
     * 6. Android intercepts callback URL (via Custom URL Scheme or App Links)
     * 7. App calls handleAuthCallback(url)
     * 8. SDK exchanges the code using code_verifier and receives tokens
     * 9. SDK creates session, updates auth state, and persists refresh token
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
     *         clientType = ClientType.MOBILE
     *     }
     * }
     *
     * // Start OAuth flow - browser opens automatically
     * client.auth.signInWithDefaultPage("yourapp://auth/callback")
     * ```
     */
    fun signInWithDefaultPage(redirectTo: String): String {
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

        // Generate PKCE pair and store verifier for later exchange
        val pkce = PKCE.generate()
        pendingPkceVerifier = pkce.codeVerifier

        val encodedRedirect = java.net.URLEncoder.encode(redirectTo, "UTF-8")
        val authUrl = "${client.baseURL}/auth/sign-in?redirect=$encodedRedirect&code_challenge=${pkce.codeChallenge}&code_challenge_method=S256"
        launcher.launch(authUrl)

        return authUrl
    }

    /**
     * Get the authentication URL without opening the browser.
     *
     * Use this if you want to control when/how to open the browser yourself.
     * Note: This method also generates and stores a PKCE verifier internally.
     *
     * @param redirectTo Callback URL where InsForge will redirect after authentication.
     * @return The authentication URL with PKCE code_challenge
     */
    fun getAuthUrl(redirectTo: String): String {
        // Generate PKCE pair and store verifier for later exchange
        val pkce = PKCE.generate()
        pendingPkceVerifier = pkce.codeVerifier

        val encodedRedirect = java.net.URLEncoder.encode(redirectTo, "UTF-8")
        return "${client.baseURL}/auth/sign-in?redirect=$encodedRedirect&code_challenge=${pkce.codeChallenge}&code_challenge_method=S256"
    }

    /**
     * Handle the callback URL from OAuth/authentication flow.
     *
     * This method extracts the exchange code from the callback URL,
     * exchanges it for tokens using the stored PKCE code_verifier,
     * creates a session, and persists the refresh token.
     *
     * Callback URL parameters:
     * - code: Exchange code to be exchanged for tokens
     *
     * @param callbackUrl The full callback URL intercepted by the app
     * @return OAuthExchangeResponse containing user and tokens
     * @throws IllegalArgumentException if exchange code is missing
     * @throws IllegalStateException if PKCE verifier is not available
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
     *                 println("Authenticated: ${result.user.email}")
     *             } catch (e: Exception) {
     *                 // Handle error
     *             }
     *         }
     *     }
     * }
     * ```
     */
    suspend fun handleAuthCallback(callbackUrl: String): OAuthExchangeResponse {
        val uri = java.net.URI(callbackUrl)
        val queryParams = parseQueryParams(uri.query ?: uri.fragment ?: "")

        val exchangeCode = queryParams["insforge_code"]
            ?: throw IllegalArgumentException("Missing exchange code in callback URL")

        val codeVerifier = pendingPkceVerifier
            ?: throw IllegalStateException("PKCE verifier not found. Did you call signInWithOAuthPage or signInWithDefaultPage first?")

        // Clear the pending verifier
        pendingPkceVerifier = null

        // Exchange the code for tokens
        return exchangeOAuthCode(exchangeCode, codeVerifier)
    }

    /**
     * Exchange OAuth authorization code for tokens.
     *
     * @param code The authorization code from the callback
     * @param codeVerifier The PKCE code verifier
     * @return OAuthExchangeResponse with user and tokens
     */
    private suspend fun exchangeOAuthCode(code: String, codeVerifier: String): OAuthExchangeResponse {
        val response = client.httpClient.post("$baseUrl/oauth/exchange") {
            parameter("client_type", config.clientType.value)
            contentType(ContentType.Application.Json)
            setBody(OAuthExchangeRequest(code, codeVerifier))
        }

        return handleAuthResponse<OAuthExchangeResponse>(response).also { result ->
            saveSession(result.user, result.accessToken, result.refreshToken)
        }
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
                    Json.decodeFromString<dev.insforge.exceptions.ErrorResponse>(errorBody)
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
