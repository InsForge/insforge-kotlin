package io.insforge.functions.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============ Function Metadata ============

@Serializable
data class FunctionMetadata(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val status: String, // "draft", "active", "error"
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deployed_at") val deployedAt: String? = null
)

@Serializable
data class FunctionDetails(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    val code: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("deployed_at") val deployedAt: String? = null
)

@Serializable
data class RuntimeStatus(
    val status: String
)

@Serializable
data class ListFunctionsResponse(
    val functions: List<FunctionMetadata>,
    val runtime: RuntimeStatus? = null
)

// ============ Create/Update Requests ============

@Serializable
data class CreateFunctionRequest(
    val name: String,
    val code: String,
    val slug: String? = null,
    val description: String? = null,
    val status: String = "active"
)

@Serializable
data class CreateFunctionResponse(
    val success: Boolean,
    val function: FunctionMetadata
)

@Serializable
data class UpdateFunctionRequest(
    val name: String? = null,
    val code: String? = null,
    val description: String? = null,
    val status: String? = null
)

@Serializable
data class UpdateFunctionResponse(
    val success: Boolean,
    val function: FunctionMetadata
)

@Serializable
data class DeleteFunctionResponse(
    val success: Boolean,
    val message: String
)
