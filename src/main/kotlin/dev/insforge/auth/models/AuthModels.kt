package dev.insforge.auth.models

import kotlinx.serialization.Serializable

// ============ User & Session ============

@Serializable
data class User(
    val id: String,
    val email: String,
    val metadata: Map<String, String>? = null,
    val emailVerified: Boolean = false,
    val providers: List<String>? = null,
    val createdAt: String,
    val updatedAt: String
)

data class Session(
    val user: User,
    val accessToken: String
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
    val user: User,
    val accessToken: String? = null,
    val requireEmailVerification: Boolean = false,
    val redirectTo: String? = null
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
    val redirectTo: String? = null
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
    val redirectTo: String? = null
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
 * Result from OAuth callback
 */
data class OAuthCallbackResult(
    val accessToken: String,
    val userId: String,
    val email: String,
    val name: String?,
    val csrfToken: String? = null
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
