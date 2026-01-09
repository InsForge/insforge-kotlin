package dev.insforge.exceptions

import kotlinx.serialization.Serializable

/**
 * Base exception for all Insforge SDK errors
 */
open class InsforgeException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * HTTP-related exception with error details from the API
 */
class InsforgeHttpException(
    val statusCode: Int,
    val error: String?,
    message: String,
    val nextActions: String? = null,
    cause: Throwable? = null
) : InsforgeException(message, cause)

/**
 * Authentication/authorization error
 */
class InsforgeAuthException(
    message: String,
    cause: Throwable? = null
) : InsforgeException(message, cause)

/**
 * Network error
 */
class InsforgeNetworkException(
    message: String,
    cause: Throwable? = null
) : InsforgeException(message, cause)

/**
 * Serialization error
 */
class InsforgeSerializationException(
    message: String,
    cause: Throwable? = null
) : InsforgeException(message, cause)

/**
 * Error response format from the API
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val statusCode: Int,
    val nextActions: String? = null
)
