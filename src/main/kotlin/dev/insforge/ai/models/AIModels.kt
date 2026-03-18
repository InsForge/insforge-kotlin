package dev.insforge.ai.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

// ============ Tool Calling Models ============

/**
 * Function definition for a tool
 *
 * @param name The name of the function to call
 * @param description A description of what the function does
 * @param parameters JSON Schema object describing the function parameters
 */
@Serializable
data class ToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

/**
 * Tool definition for chat completion
 *
 * @param type The type of tool (currently only "function")
 * @param function The function definition
 */
@Serializable
data class Tool(
    val type: String,
    val function: ToolFunction
)

/**
 * Function details within a tool call response
 *
 * @param name The name of the function that was called
 * @param arguments The arguments passed to the function as a JSON string
 */
@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String
)

/**
 * Tool call returned by the model in a chat completion response
 *
 * @param id Unique identifier for the tool call
 * @param type The type of tool call (currently only "function")
 * @param function The function that was called with its arguments
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: ToolCallFunction
)

/**
 * Controls which tool the model should call.
 *
 * - [Auto]: Model decides whether to call a tool (default)
 * - [None]: Model will not call any tool
 * - [Required]: Model must call at least one tool
 * - [Function]: Model must call the specified function
 */
@Serializable(with = ToolChoiceSerializer::class)
sealed class ToolChoice {
    object Auto : ToolChoice()
    object None : ToolChoice()
    object Required : ToolChoice()
    data class Function(val name: String) : ToolChoice()
}

/**
 * Custom serializer for ToolChoice to handle union of string and object formats
 */
object ToolChoiceSerializer : KSerializer<ToolChoice> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ToolChoice")

    override fun serialize(encoder: Encoder, value: ToolChoice) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is ToolChoice.Auto -> JsonPrimitive("auto")
            is ToolChoice.None -> JsonPrimitive("none")
            is ToolChoice.Required -> JsonPrimitive("required")
            is ToolChoice.Function -> buildJsonObject {
                put("type", JsonPrimitive("function"))
                putJsonObject("function") {
                    put("name", JsonPrimitive(value.name))
                }
            }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ToolChoice {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> when (element.content) {
                "auto" -> ToolChoice.Auto
                "none" -> ToolChoice.None
                "required" -> ToolChoice.Required
                else -> throw IllegalArgumentException("Unknown tool choice: ${element.content}")
            }
            is JsonObject -> {
                val name = element["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing function name in tool choice")
                ToolChoice.Function(name)
            }
            else -> throw IllegalArgumentException("Expected string or object for ToolChoice")
        }
    }
}

// ============ Chat Models ============

/**
 * Image detail level for vision models
 */
enum class ImageDetail {
    @SerialName("auto") AUTO,
    @SerialName("low") LOW,
    @SerialName("high") HIGH
}

/**
 * Audio format for audio input
 */
enum class AudioFormat {
    @SerialName("wav") WAV,
    @SerialName("mp3") MP3,
    @SerialName("aiff") AIFF,
    @SerialName("aac") AAC,
    @SerialName("ogg") OGG,
    @SerialName("flac") FLAC,
    @SerialName("m4a") M4A
}

/**
 * Text content part for multimodal messages
 */
@Serializable
@SerialName("text")
data class TextContent(
    val type: String = "text",
    val text: String
) : MessageContent()

/**
 * Image URL configuration
 */
@Serializable
data class ImageUrlConfig(
    val url: String,
    val detail: ImageDetail? = null
)

/**
 * Image content part for multimodal messages
 *
 * @param imageUrl Image URL configuration. URL can be:
 *   - Public URL: "https://example.com/image.jpg"
 *   - Base64 data URI: "data:image/jpeg;base64,/9j/4AAQ..."
 */
@Serializable
@SerialName("image_url")
data class ImageContent(
    val type: String = "image_url",
    @SerialName("image_url")
    val imageUrl: ImageUrlConfig
) : MessageContent()

/**
 * Audio input configuration
 */
@Serializable
data class AudioInputConfig(
    val data: String, // Base64-encoded audio data
    val format: AudioFormat
)

/**
 * Audio content part for multimodal messages
 *
 * @param inputAudio Audio configuration with base64-encoded data
 */
@Serializable
@SerialName("input_audio")
data class AudioContent(
    val type: String = "input_audio",
    @SerialName("input_audio")
    val inputAudio: AudioInputConfig
) : MessageContent()

/**
 * File configuration for PDFs and documents
 */
@Serializable
data class FileConfig(
    val filename: String,
    @SerialName("file_data")
    val fileData: String // Public URL or base64 data URI
)

/**
 * File content part for PDFs and other documents
 *
 * @param file File configuration with filename and data
 */
@Serializable
@SerialName("file")
data class FileContent(
    val type: String = "file",
    val file: FileConfig
) : MessageContent()

/**
 * Base class for message content parts (text, image, audio, file)
 */
@Serializable(with = MessageContentSerializer::class)
sealed class MessageContent

// Json instance that always encodes defaults (needed for type fields) but excludes nulls
private val jsonWithDefaults = Json {
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Custom serializer for MessageContent to handle polymorphic serialization
 */
object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MessageContent")

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is TextContent -> jsonWithDefaults.encodeToJsonElement(TextContent.serializer(), value)
            is ImageContent -> jsonWithDefaults.encodeToJsonElement(ImageContent.serializer(), value)
            is AudioContent -> jsonWithDefaults.encodeToJsonElement(AudioContent.serializer(), value)
            is FileContent -> jsonWithDefaults.encodeToJsonElement(FileContent.serializer(), value)
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val type = element["type"]?.jsonPrimitive?.content

        return when (type) {
            "text" -> Json.decodeFromJsonElement(TextContent.serializer(), JsonObject(element))
            "image_url" -> Json.decodeFromJsonElement(ImageContent.serializer(), JsonObject(element))
            "input_audio" -> Json.decodeFromJsonElement(AudioContent.serializer(), JsonObject(element))
            "file" -> Json.decodeFromJsonElement(FileContent.serializer(), JsonObject(element))
            else -> throw IllegalArgumentException("Unknown content type: $type")
        }
    }
}

/**
 * Chat message content - can be a simple string or an array of content parts
 */
@Serializable(with = ChatMessageContentSerializer::class)
sealed class ChatMessageContent {
    /**
     * Simple text content (backward compatible)
     */
    data class Text(val text: String) : ChatMessageContent()

    /**
     * Multimodal content with multiple parts (text, images, audio, files)
     */
    data class Parts(val parts: List<MessageContent>) : ChatMessageContent()
}

/**
 * Custom serializer for ChatMessageContent
 */
object ChatMessageContentSerializer : KSerializer<ChatMessageContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatMessageContent")

    override fun serialize(encoder: Encoder, value: ChatMessageContent) {
        val jsonEncoder = encoder as JsonEncoder
        val element = when (value) {
            is ChatMessageContent.Text -> JsonPrimitive(value.text)
            is ChatMessageContent.Parts -> JsonArray(value.parts.map { part ->
                when (part) {
                    is TextContent -> jsonWithDefaults.encodeToJsonElement(TextContent.serializer(), part)
                    is ImageContent -> jsonWithDefaults.encodeToJsonElement(ImageContent.serializer(), part)
                    is AudioContent -> jsonWithDefaults.encodeToJsonElement(AudioContent.serializer(), part)
                    is FileContent -> jsonWithDefaults.encodeToJsonElement(FileContent.serializer(), part)
                }
            })
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ChatMessageContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()

        return when (element) {
            is JsonPrimitive -> ChatMessageContent.Text(element.content)
            is JsonArray -> ChatMessageContent.Parts(element.map { partElement ->
                val obj = partElement.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                when (type) {
                    "text" -> Json.decodeFromJsonElement(TextContent.serializer(), partElement)
                    "image_url" -> Json.decodeFromJsonElement(ImageContent.serializer(), partElement)
                    "input_audio" -> Json.decodeFromJsonElement(AudioContent.serializer(), partElement)
                    "file" -> Json.decodeFromJsonElement(FileContent.serializer(), partElement)
                    else -> throw IllegalArgumentException("Unknown content type: $type")
                }
            })
            else -> throw IllegalArgumentException("Expected string or array for ChatMessageContent")
        }
    }
}

/**
 * Chat message with support for multimodal content
 *
 * @param role Message role: "user", "assistant", "system", or "tool"
 * @param content Message content - can be a simple string or multimodal content
 * @param toolCalls Tool calls made by the assistant
 * @param toolCallId The tool call ID this message responds to
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: ChatMessageContent? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null
) {
    companion object {
        /**
         * Create a simple text message
         */
        fun text(role: String, content: String): ChatMessage {
            return ChatMessage(role, ChatMessageContent.Text(content))
        }

        /**
         * Create a user message with text
         */
        fun user(content: String): ChatMessage = text("user", content)

        /**
         * Create an assistant message with text
         */
        fun assistant(content: String): ChatMessage = text("assistant", content)

        /**
         * Create a system message with text
         */
        fun system(content: String): ChatMessage = text("system", content)

        /**
         * Create a tool response message
         *
         * @param toolCallId The ID of the tool call this message is responding to
         * @param content The result of the tool call as a string
         */
        fun tool(toolCallId: String, content: String): ChatMessage {
            return ChatMessage(
                role = "tool",
                content = ChatMessageContent.Text(content),
                toolCallId = toolCallId
            )
        }

        /**
         * Create a multimodal message with multiple content parts
         */
        fun multimodal(role: String, vararg parts: MessageContent): ChatMessage {
            return ChatMessage(role, ChatMessageContent.Parts(parts.toList()))
        }

        /**
         * Create a user message with text and images
         */
        fun userWithImages(text: String, vararg imageUrls: String): ChatMessage {
            val parts = mutableListOf<MessageContent>()
            parts.add(TextContent(text = text))
            imageUrls.forEach { url ->
                parts.add(ImageContent(imageUrl = ImageUrlConfig(url = url)))
            }
            return ChatMessage("user", ChatMessageContent.Parts(parts))
        }

        /**
         * Create a user message with text and a PDF file
         */
        fun userWithFile(text: String, filename: String, fileData: String): ChatMessage {
            val parts = listOf(
                TextContent(text = text),
                FileContent(file = FileConfig(filename = filename, fileData = fileData))
            )
            return ChatMessage("user", ChatMessageContent.Parts(parts))
        }
    }
}

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
    val thinking: Boolean? = null,
    val tools: List<Tool>? = null,
    val toolChoice: ToolChoice? = null,
    val parallelToolCalls: Boolean? = null
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
    val metadata: CompletionMetadata? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null
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

/**
 * Partial function data received during a streaming tool call.
 * Both fields may be null or incomplete until the stream is done.
 */
@Serializable
data class StreamToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

/**
 * A single tool call chunk received during streaming.
 * [index] identifies which tool call this chunk belongs to (for parallel tool calls).
 * [id] is only present on the first chunk for a given tool call.
 */
@Serializable
data class StreamToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: StreamToolCallFunction? = null
)

@Serializable
data class StreamChunk(
    val choices: List<StreamChoice>? = null,
    @SerialName("tool_calls")
    val toolCalls: List<StreamToolCall>? = null,
    val done: Boolean? = null,
    val tokenUsage: TokenUsage? = null
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta
)

@Serializable
data class StreamDelta(
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<StreamToolCall>? = null
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
    val inputModality: List<String>? = null,
    val outputModality: List<String>? = null,
    val priceLevel: Int? = null
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
