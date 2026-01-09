package dev.insforge.ai.models

import kotlinx.serialization.Serializable

// ============ Chat Models ============

@Serializable
data class ChatMessage(
    val role: String, // "user", "assistant", "system"
    val content: String
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val systemPrompt: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val success: Boolean,
    val content: String,
    val metadata: CompletionMetadata
)

@Serializable
data class CompletionMetadata(
    val model: String,
    val usage: TokenUsage
)

@Serializable
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

// ============ Streaming Models ============

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice>
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta
)

@Serializable
data class StreamDelta(
    val content: String? = null
)

// ============ Image Generation Models ============

@Serializable
data class ImageGenerationRequest(
    val model: String,
    val prompt: String
)

@Serializable
data class ImageGenerationResponse(
    val model: String,
    val images: List<GeneratedImage>,
    val text: String? = null,
    val count: Int,
    val metadata: ImageMetadata,
    val nextActions: String
)

@Serializable
data class GeneratedImage(
    val type: String, // "image_url"
    val image_url: ImageUrl
)

@Serializable
data class ImageUrl(
    val url: String // Can be URL or data:image base64
)

@Serializable
data class ImageMetadata(
    val model: String,
    val revisedPrompt: String? = null,
    val usage: TokenUsage
)

// ============ Model List Models ============

@Serializable
data class AIModel(
    val id: String,
    val modelId: String,
    val provider: String,
    val inputModality: List<String>,
    val outputModality: List<String>,
    val priceLevel: Int
)

// ============ Configuration Models ============

@Serializable
data class ConfigurationUsageStats(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalTokens: Int,
    val totalImageCount: Int,
    val totalRequests: Int
)

@Serializable
data class AIConfiguration(
    val id: String,
    val inputModality: List<String>? = null,
    val outputModality: List<String>? = null,
    val provider: String,
    val modelId: String,
    val systemPrompt: String? = null,
    val usageStats: ConfigurationUsageStats? = null
)

@Serializable
data class CreateConfigurationRequest(
    val inputModality: List<String>,
    val outputModality: List<String>,
    val provider: String,
    val modelId: String,
    val systemPrompt: String? = null
)

@Serializable
data class CreateConfigurationResponse(
    val id: String,
    val message: String
)

@Serializable
data class UpdateConfigurationResponse(
    val message: String
)

@Serializable
data class DeleteConfigurationResponse(
    val message: String
)

// ============ Usage Models ============

@Serializable
data class AIUsageSummary(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalTokens: Int,
    val totalImageCount: Int,
    val totalRequests: Int
)

@Serializable
data class AIUsageRecord(
    val id: String,
    val configId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val imageCount: Int? = null,
    val imageResolution: String? = null,
    val createdAt: String,
    val modelId: String,
    val model: String? = null,
    val provider: String? = null,
    val inputModality: List<String>? = null,
    val outputModality: List<String>? = null
)

@Serializable
data class AIUsageRecordsResponse(
    val records: List<AIUsageRecord>,
    val total: Int
)
