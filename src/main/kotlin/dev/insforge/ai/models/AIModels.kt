package dev.insforge.ai.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

// ============ Chat Models ============

@Serializable
data class ChatMessage(
    val role: String, // "user", "assistant", "system"
    val content: String
)

// ============ Plugin Models ============

/**
 * Web search engine options
 */
enum class WebSearchEngine {
    @SerialName("native") NATIVE,
    @SerialName("exa") EXA
}

/**
 * Web search plugin configuration
 *
 * @param enabled Enable web search integration
 * @param engine Search engine selection (native, exa, or null for auto-select)
 * @param maxResults Maximum number of search results to include (1-10, default 5)
 * @param searchPrompt Custom prompt for attaching search results to the message
 */
@Serializable
data class WebSearchPlugin(
    val enabled: Boolean,
    val engine: WebSearchEngine? = null,
    val maxResults: Int? = null,
    val searchPrompt: String? = null
)

/**
 * PDF processing engine options
 */
enum class PdfEngine {
    @SerialName("pdf-text") PDF_TEXT,
    @SerialName("mistral-ocr") MISTRAL_OCR,
    @SerialName("native") NATIVE
}

/**
 * PDF parser configuration
 *
 * @param engine PDF processing engine:
 *   - PDF_TEXT: Best for well-structured PDFs with clear text content (Free)
 *   - MISTRAL_OCR: Best for scanned documents or PDFs with images ($2 per 1,000 pages)
 *   - NATIVE: Only for models with native file support (charged as input tokens)
 */
@Serializable
data class PdfParserConfig(
    val engine: PdfEngine? = null
)

/**
 * File parser plugin configuration
 *
 * @param enabled Enable file parsing for PDFs in messages
 * @param pdf PDF-specific configuration
 */
@Serializable
data class FileParserPlugin(
    val enabled: Boolean,
    val pdf: PdfParserConfig? = null
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val systemPrompt: String? = null,
    val webSearch: WebSearchPlugin? = null,
    val fileParser: FileParserPlugin? = null,
    val thinking: Boolean? = null
)

// ============ Response Models ============

/**
 * URL citation from web search results
 */
@Serializable
data class UrlCitation(
    val url: String,
    val title: String? = null,
    val content: String? = null,
    val startIndex: Int? = null,
    val endIndex: Int? = null
)

/**
 * Annotation containing URL citation
 */
@Serializable
data class UrlCitationAnnotation(
    val type: String, // "url_citation"
    val urlCitation: UrlCitation? = null
)

@Serializable
data class ChatCompletionResponse(
    val text: String? = null,
    val annotations: List<UrlCitationAnnotation>? = null,
    val metadata: CompletionMetadata? = null
) {
    /**
     * Alias for [text] property for backward compatibility.
     */
    val content: String? get() = text
}

@Serializable
data class CompletionMetadata(
    val model: String,
    val usage: TokenUsage? = null
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

// ============ Embeddings Models ============

/**
 * Encoding format for embeddings
 */
enum class EmbeddingEncodingFormat {
    @SerialName("float") FLOAT,
    @SerialName("base64") BASE64
}

@Serializable
data class EmbeddingsRequest(
    val model: String,
    val input: EmbeddingsInput,
    @SerialName("encoding_format")
    val encodingFormat: EmbeddingEncodingFormat? = null,
    val dimensions: Int? = null
)

/**
 * Input for embeddings - can be a single string or array of strings
 */
@Serializable(with = EmbeddingsInputSerializer::class)
sealed class EmbeddingsInput {
    data class Single(val text: String) : EmbeddingsInput()
    data class Multiple(val texts: List<String>) : EmbeddingsInput()
}

@Serializable
data class EmbeddingsResponse(
    val `object`: String, // "list"
    val data: List<EmbeddingObject>,
    val metadata: EmbeddingsMetadata? = null
)

@Serializable
data class EmbeddingObject(
    val `object`: String, // "embedding"
    val embedding: List<Double>,
    val index: Int
)

@Serializable
data class EmbeddingsMetadata(
    val model: String,
    val usage: EmbeddingsTokenUsage? = null
)

@Serializable
data class EmbeddingsTokenUsage(
    val promptTokens: Int? = null,
    val totalTokens: Int? = null
)

/**
 * Custom serializer for EmbeddingsInput to handle both single string and array of strings
 */
object EmbeddingsInputSerializer : KSerializer<EmbeddingsInput> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("EmbeddingsInput")

    override fun serialize(encoder: Encoder, value: EmbeddingsInput) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is EmbeddingsInput.Single -> JsonPrimitive(value.text)
            is EmbeddingsInput.Multiple -> JsonArray(value.texts.map { JsonPrimitive(it) })
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): EmbeddingsInput {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> EmbeddingsInput.Single(element.content)
            is JsonArray -> EmbeddingsInput.Multiple(element.map { it.jsonPrimitive.content })
            else -> throw IllegalArgumentException("Expected string or array for EmbeddingsInput")
        }
    }
}
