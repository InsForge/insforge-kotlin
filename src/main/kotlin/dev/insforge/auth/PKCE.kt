package dev.insforge.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (Proof Key for Code Exchange) utility for OAuth 2.0 security.
 *
 * PKCE is used to prevent authorization code interception attacks.
 * It works by generating a random code_verifier and its SHA-256 hash (code_challenge).
 * The code_challenge is sent with the authorization request, and the code_verifier
 * is sent when exchanging the authorization code for tokens.
 */
object PKCE {
    private const val CODE_VERIFIER_LENGTH = 64

    /**
     * Generate a new PKCE code verifier and challenge pair.
     *
     * @return PKCEPair containing both the verifier and challenge
     */
    fun generate(): PKCEPair {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        return PKCEPair(codeVerifier, codeChallenge)
    }

    /**
     * Generate a random code verifier.
     *
     * The code verifier is a cryptographically random string using
     * characters [A-Z], [a-z], [0-9], "-", ".", "_", "~".
     */
    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(CODE_VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
            .take(CODE_VERIFIER_LENGTH)
    }

    /**
     * Generate a code challenge from a code verifier using SHA-256.
     *
     * code_challenge = BASE64URL(SHA256(code_verifier))
     */
    private fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(hash)
    }
}

/**
 * PKCE code verifier and challenge pair.
 *
 * @param codeVerifier The secret code verifier (keep this secure, send only during token exchange)
 * @param codeChallenge The SHA-256 hash of the verifier (send with authorization request)
 */
data class PKCEPair(
    val codeVerifier: String,
    val codeChallenge: String
)
