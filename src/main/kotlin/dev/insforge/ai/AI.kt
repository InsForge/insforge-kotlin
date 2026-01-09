package dev.insforge.ai

import dev.insforge.InsforgeClient
import dev.insforge.ai.models.*
import dev.insforge.exceptions.InsforgeHttpException
import dev.insforge.plugins.InsforgePlugin
import dev.insforge.plugins.InsforgePluginProvider
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * AI module for InsForge (Chat completion and image generation via OpenRouter)
 *
 * Install this module in your Insforge client:
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(AI)
 * }
 *
 * // List available models
 * val models = client.ai.listModels()
 *
 * // Chat completion
 * val response = client.ai.chatCompletion(
 *     model = "openai/gpt-4",
 *     messages = listOf(ChatMessage("user", "What is Kotlin?"))
 * )
 *
 * // Image generation
 * val images = client.ai.generateImage(
 *     model = "openai/dall-e-3",
 *     prompt = "A sunset over mountains"
 * )
 * ```
 */
class AI internal constructor(
    private val client: InsforgeClient,
    private val config: AIConfig
) : InsforgePlugin<AIConfig> {

    override val key: String = AI.key

    private val baseUrl = "${client.baseURL}/api/ai"

    // ============ Models ============

    /**
     * List all available AI models
     */
    suspend fun listModels(): List<AIModel> {
        val response = client.httpClient.get("$baseUrl/models")
        return handleResponse(response)
    }

    // ============ Chat Completion ============

    /**
     * Generate chat completion
     *
     * @param model Model identifier (e.g., "openai/gpt-4")
     * @param messages List of chat messages
     * @param stream Enable streaming response
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param topP Nucleus sampling parameter
     * @param systemPrompt Optional system prompt
     */
    suspend fun chatCompletion(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean = false,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        systemPrompt: String? = null
    ): ChatCompletionResponse {
        val response = client.httpClient.post("$baseUrl/chat/completion") {
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = stream,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                systemPrompt = systemPrompt
            ))
        }
        return handleResponse(response)
    }

    /**
     * Generate chat completion with streaming
     *
     * @param model Model identifier
     * @param messages List of chat messages
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param topP Nucleus sampling parameter
     * @param systemPrompt Optional system prompt
     * @return Flow of streaming chunks
     */
    fun chatCompletionStream(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        systemPrompt: String? = null
    ): Flow<String> = flow {
        val response = client.httpClient.post("$baseUrl/chat/completion") {
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                systemPrompt = systemPrompt
            ))
        }

        val channel: ByteReadChannel = response.body()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ")
                if (data != "[DONE]") {
                    try {
                        val chunk = Json.decodeFromString<StreamChunk>(data)
                        chunk.choices.firstOrNull()?.delta?.content?.let { emit(it) }
                    } catch (e: Exception) {
                        // Ignore parsing errors
                    }
                }
            }
        }
    }

    // ============ Image Generation ============

    /**
     * Generate images
     *
     * @param model Model identifier (e.g., "openai/dall-e-3")
     * @param prompt Text prompt describing the desired image
     */
    suspend fun generateImage(
        model: String,
        prompt: String
    ): ImageGenerationResponse {
        val response = client.httpClient.post("$baseUrl/image/generation") {
            contentType(ContentType.Application.Json)
            setBody(ImageGenerationRequest(
                model = model,
                prompt = prompt
            ))
        }
        return handleResponse(response)
    }

    // ============ Configuration Management (Admin) ============

    /**
     * List AI configurations (admin only)
     */
    suspend fun listConfigurations(): List<AIConfiguration> {
        val response = client.httpClient.get("$baseUrl/configurations")
        return handleResponse(response)
    }

    /**
     * Create AI configuration (admin only)
     *
     * @param inputModality Input modality types
     * @param outputModality Output modality types
     * @param provider Provider name
     * @param modelId Model identifier
     * @param systemPrompt Optional system prompt
     */
    suspend fun createConfiguration(
        inputModality: List<String>,
        outputModality: List<String>,
        provider: String,
        modelId: String,
        systemPrompt: String? = null
    ): CreateConfigurationResponse {
        val response = client.httpClient.post("$baseUrl/configurations") {
            contentType(ContentType.Application.Json)
            setBody(CreateConfigurationRequest(
                inputModality = inputModality,
                outputModality = outputModality,
                provider = provider,
                modelId = modelId,
                systemPrompt = systemPrompt
            ))
        }
        return handleResponse(response)
    }

    /**
     * Update AI configuration (admin only)
     *
     * @param configId Configuration ID
     * @param systemPrompt Updated system prompt
     */
    suspend fun updateConfiguration(
        configId: String,
        systemPrompt: String
    ): UpdateConfigurationResponse {
        val response = client.httpClient.patch("$baseUrl/configurations/$configId") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("systemPrompt" to systemPrompt))
        }
        return handleResponse(response)
    }

    /**
     * Delete AI configuration (admin only)
     *
     * @param configId Configuration ID
     */
    suspend fun deleteConfiguration(configId: String): DeleteConfigurationResponse {
        val response = client.httpClient.delete("$baseUrl/configurations/$configId")
        return handleResponse(response)
    }

    // ============ Usage Statistics (Admin) ============

    /**
     * Get usage summary (admin only)
     *
     * @param configId Optional configuration ID filter
     * @param startDate Optional start date
     * @param endDate Optional end date
     */
    suspend fun getUsageSummary(
        configId: String? = null,
        startDate: String? = null,
        endDate: String? = null
    ): AIUsageSummary {
        val response = client.httpClient.get("$baseUrl/usage/summary") {
            configId?.let { parameter("configId", it) }
            startDate?.let { parameter("startDate", it) }
            endDate?.let { parameter("endDate", it) }
        }
        return handleResponse(response)
    }

    /**
     * Get usage records (admin only)
     *
     * @param startDate Optional start date
     * @param endDate Optional end date
     * @param limit Number of records to return
     * @param offset Number of records to skip
     */
    suspend fun getUsageRecords(
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): AIUsageRecordsResponse {
        val response = client.httpClient.get("$baseUrl/usage") {
            startDate?.let { parameter("startDate", it) }
            endDate?.let { parameter("endDate", it) }
            parameter("limit", limit.toString())
            parameter("offset", offset.toString())
        }
        return handleResponse(response)
    }

    // ============ Helper Methods ============

    @PublishedApi
    internal suspend inline fun <reified T> handleResponse(response: HttpResponse): T {
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created, HttpStatusCode.NoContent -> {
                if (T::class == Unit::class) {
                    Unit as T
                } else {
                    response.body()
                }
            }
            else -> throw handleError(response)
        }
    }

    @PublishedApi
    internal suspend fun handleError(response: HttpResponse): InsforgeHttpException {
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

        return InsforgeHttpException(
            statusCode = error.statusCode,
            error = error.error,
            message = error.message,
            nextActions = error.nextActions
        )
    }

    companion object : InsforgePluginProvider<AIConfig, AI> {
        override val key: String = "ai"

        override fun createConfig(configure: AIConfig.() -> Unit): AIConfig {
            return AIConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: AIConfig): AI {
            return AI(client, config)
        }
    }
}

/**
 * Extension property for accessing AI module
 */
val InsforgeClient.ai: AI
    get() = plugin(AI.key)
