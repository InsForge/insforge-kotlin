package dev.insforge.auth.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============ User & Session ============

/**
 * User information returned from authentication endpoints.
 *
 * @param id Unique user identifier
 * @param email User's email address
 * @param emailVerified Whether the email has been verified
 * @param providers List of OAuth providers linked to this account
 * @param profile User profile containing name, avatar_url, and custom fields
 * @param metadata System metadata (nullable)
 * @param createdAt ISO 8601 timestamp of user creation
 * @param updatedAt ISO 8601 timestamp of last update
 */
@Serializable
data class User(
    val id: String,
    val email: String,
    val emailVerified: Boolean = false,
    val providers: List<String>? = null,
    val profile: Map<String, String?>? = null,
    val metadata: Map<String, String>? = null,
    val createdAt: String,
    val updatedAt: String
) {
    /**
     * Convenience property to get user's display name from profile.
     */
    val name: String?
        get() = profile?.get("name")

    /**
     * Convenience property to get user's avatar URL from profile.
     */
    val avatarUrl: String?
        get() = profile?.get("avatar_url")
}

/**
 * Session containing user information and tokens.
 *
 * @param user The authenticated user
 * @param accessToken JWT access token for API requests (short-lived, ~15 minutes)
 * @param refreshToken Token for refreshing the access token (long-lived, ~7 days).
 *                     Only available for mobile/desktop clients.
 */
data class Session(
    val user: User,
    val accessToken: String,
    val refreshToken: String? = null
)

// ============ Sign Up / Sign In ============

@Serializable
data class SignUpRequest(
    val email: String,
    val password: String,
    val name: String? = null
)

@Serializable
data class SignUpResponse(
    val user: User? = null,
    val accessToken: String? = null,
    val requireEmailVerification: Boolean = false,
    val redirectTo: String? = null,
    val csrfToken: String? = null,
    val refreshToken: String? = null
)

@Serializable
data class SignInRequest(
    val email: String,
    val password: String
)

@Serializable
data class SignInResponse(
    val user: User,
    val accessToken: String,
    val redirectTo: String? = null,
    val csrfToken: String? = null,
    val refreshToken: String? = null
)

// ============ Email Verification ============

@Serializable
data class VerifyEmailRequest(
    val email: String? = null,
    val otp: String
)

@Serializable
data class VerifyEmailResponse(
    val user: User,
    val accessToken: String,
    val redirectTo: String? = null,
    val refreshToken: String? = null
)

// ============ Password Reset ============

@Serializable
data class ExchangeResetTokenRequest(
    val email: String,
    val code: String
)

@Serializable
data class ResetTokenResponse(
    val token: String,
    val expiresAt: String
)

@Serializable
data class ResetPasswordRequest(
    val newPassword: String,
    val otp: String
)

// ============ OAuth ============

@Serializable
data class OAuthUrlResponse(
    val authUrl: String
)

/**
 * Request to exchange OAuth code for tokens (PKCE flow)
 */
@Serializable
data class OAuthExchangeRequest(
    val code: String,
    @SerialName("code_verifier")
    val codeVerifier: String
)

/**
 * Response from OAuth code exchange
 */
@Serializable
data class OAuthExchangeResponse(
    val user: User,
    val accessToken: String,
    val refreshToken: String? = null,
    val redirectTo: String? = null
)

/**
 * Result from OAuth callback (contains the exchange code)
 */
data class OAuthCallbackResult(
    val exchangeCode: String
)

// ============ Token Refresh ============

/**
 * Request to refresh access token
 */
@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)

/**
 * Response from token refresh
 */
@Serializable
data class RefreshTokenResponse(
    val user: User,
    val accessToken: String,
    val refreshToken: String? = null
)

/**
 * Supported OAuth providers
 */
enum class OAuthProvider(val value: String) {
    GOOGLE("google"),
    GITHUB("github"),
    DISCORD("discord"),
    LINKEDIN("linkedin"),
    FACEBOOK("facebook"),
    INSTAGRAM("instagram"),
    TIKTOK("tiktok"),
    APPLE("apple"),
    X("x"),
    SPOTIFY("spotify"),
    MICROSOFT("microsoft");

    override fun toString(): String = value
}

// ============ Profile ============

@Serializable
data class ProfileResponse(
    val id: String,
    val profile: Map<String, String>?
)

@Serializable
data class CurrentUserResponse(
    val user: CurrentUser
)

@Serializable
data class CurrentUser(
    val id: String,
    val email: String,
    val role: String
)

// ============ Admin ============

@Serializable
data class ListUsersResponse(
    val data: List<User>,
    val pagination: Pagination
)

@Serializable
data class Pagination(
    val offset: Int,
    val limit: Int,
    val total: Int
)

@Serializable
data class DeleteUsersResponse(
    val message: String,
    val deletedCount: Int
)

// ============ Configuration ============

@Serializable
data class AuthPublicConfig(
    val oAuthProviders: List<String>? = null,
    val requireEmailVerification: Boolean,
    val passwordMinLength: Int,
    val requireNumber: Boolean,
    val requireLowercase: Boolean,
    val requireUppercase: Boolean,
    val requireSpecialChar: Boolean,
    val verifyEmailRedirectTo: String? = null,
    val resetPasswordRedirectTo: String? = null,
    val verifyEmailMethod: String, // "code" or "link"
    val resetPasswordMethod: String // "code" or "link"
)
