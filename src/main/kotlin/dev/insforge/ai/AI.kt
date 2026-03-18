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
     * @param webSearch Web search plugin configuration for real-time information
     * @param fileParser File parser plugin configuration for PDF processing
     * @param thinking Enable extended reasoning capabilities (only works with Anthropic models with :thinking suffix)
     * @param tools List of tools the model may call
     * @param toolChoice Controls which tool the model should call
     * @param parallelToolCalls Allow the model to call multiple tools in parallel
     */
    suspend fun chatCompletion(
        model: String,
        messages: List<ChatMessage>,
        stream: Boolean = false,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        systemPrompt: String? = null,
        webSearch: WebSearchPlugin? = null,
        fileParser: FileParserPlugin? = null,
        thinking: Boolean? = null,
        tools: List<Tool>? = null,
        toolChoice: ToolChoice? = null,
        parallelToolCalls: Boolean? = null
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
                systemPrompt = systemPrompt,
                webSearch = webSearch,
                fileParser = fileParser,
                thinking = thinking,
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls
            ))
        }
        return handleResponse(response)
    }

    /**
     * Generate chat completion with web search enabled
     *
     * @param model Model identifier (e.g., "openai/gpt-4")
     * @param messages List of chat messages
     * @param engine Search engine (NATIVE or EXA, null for auto-select)
     * @param maxResults Maximum number of search results (1-10, default 5)
     * @param searchPrompt Custom prompt for attaching search results
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param systemPrompt Optional system prompt
     */
    suspend fun chatCompletionWithWebSearch(
        model: String,
        messages: List<ChatMessage>,
        engine: WebSearchEngine? = null,
        maxResults: Int? = null,
        searchPrompt: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null
    ): ChatCompletionResponse {
        return chatCompletion(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            webSearch = WebSearchPlugin(
                enabled = true,
                engine = engine,
                maxResults = maxResults,
                searchPrompt = searchPrompt
            )
        )
    }

    /**
     * Generate chat completion with extended reasoning (thinking mode)
     *
     * Note: Only works with Anthropic models. The :thinking suffix will be appended
     * to the model ID if not already present.
     *
     * @param model Model identifier (e.g., "anthropic/claude-3.5-sonnet")
     * @param messages List of chat messages
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param systemPrompt Optional system prompt
     */
    suspend fun chatCompletionWithThinking(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null
    ): ChatCompletionResponse {
        return chatCompletion(
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            thinking = true
        )
    }

    /**
     * Generate chat completion with images (vision models)
     *
     * @param model Vision-capable model identifier (e.g., "openai/gpt-4-vision", "anthropic/claude-3.5-sonnet")
     * @param text Text prompt to accompany the images
     * @param imageUrls List of image URLs (public URLs or base64 data URIs)
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param systemPrompt Optional system prompt
     *
     * Example:
     * ```kotlin
     * val response = client.ai.chatCompletionWithImages(
     *     model = "openai/gpt-4-vision",
     *     text = "What is in this image?",
     *     imageUrls = listOf("https://example.com/image.jpg")
     * )
     * ```
     */
    suspend fun chatCompletionWithImages(
        model: String,
        text: String,
        imageUrls: List<String>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null
    ): ChatCompletionResponse {
        val message = ChatMessage.userWithImages(text, *imageUrls.toTypedArray())
        return chatCompletion(
            model = model,
            messages = listOf(message),
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt
        )
    }

    /**
     * Generate chat completion with a PDF file
     *
     * @param model Model identifier (e.g., "anthropic/claude-3.5-sonnet")
     * @param text Text prompt to accompany the file
     * @param filename Filename with extension (e.g., "document.pdf")
     * @param fileData File data - can be a public URL or base64 data URI
     * @param pdfEngine PDF processing engine (optional)
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param systemPrompt Optional system prompt
     *
     * Example:
     * ```kotlin
     * val response = client.ai.chatCompletionWithFile(
     *     model = "anthropic/claude-3.5-sonnet",
     *     text = "Summarize this document",
     *     filename = "report.pdf",
     *     fileData = "https://example.com/report.pdf",
     *     pdfEngine = PdfEngine.MISTRAL_OCR
     * )
     * ```
     */
    suspend fun chatCompletionWithFile(
        model: String,
        text: String,
        filename: String,
        fileData: String,
        pdfEngine: PdfEngine? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
        systemPrompt: String? = null
    ): ChatCompletionResponse {
        val message = ChatMessage.userWithFile(text, filename, fileData)
        return chatCompletion(
            model = model,
            messages = listOf(message),
            temperature = temperature,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            fileParser = FileParserPlugin(
                enabled = true,
                pdf = pdfEngine?.let { PdfParserConfig(engine = it) }
            )
        )
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
     * @param webSearch Web search plugin configuration
     * @param fileParser File parser plugin configuration
     * @param thinking Enable extended reasoning capabilities
     * @param tools List of tools the model may call
     * @param toolChoice Controls which tool the model should call
     * @param parallelToolCalls Allow the model to call multiple tools in parallel
     * @return Flow of streaming text chunks
     */
    fun chatCompletionStream(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        systemPrompt: String? = null,
        webSearch: WebSearchPlugin? = null,
        fileParser: FileParserPlugin? = null,
        thinking: Boolean? = null,
        tools: List<Tool>? = null,
        toolChoice: ToolChoice? = null,
        parallelToolCalls: Boolean? = null
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
                systemPrompt = systemPrompt,
                webSearch = webSearch,
                fileParser = fileParser,
                thinking = thinking,
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls
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
                        // Ignore parsing errors for individual chunks
                    }
                }
            }
        }
    }

    /**
     * Generate chat completion with streaming, with support for tool calls.
     *
     * Text delta chunks are emitted as they arrive. Once the stream ends, a final
     * [ChatCompletionResponse] containing the assembled [ToolCall] list is emitted.
     *
     * @param model Model identifier
     * @param messages List of chat messages
     * @param temperature Controls randomness (0-2)
     * @param maxTokens Maximum tokens to generate
     * @param topP Nucleus sampling parameter
     * @param systemPrompt Optional system prompt
     * @param webSearch Web search plugin configuration
     * @param fileParser File parser plugin configuration
     * @param thinking Enable extended reasoning capabilities
     * @param tools List of tools the model may call
     * @param toolChoice Controls which tool the model should call
     * @param parallelToolCalls Allow the model to call multiple tools in parallel
     * @return Flow of [ChatCompletionResponse] — delta text chunks during streaming,
     *         then a final emission with assembled tool calls
     */
    fun chatCompletionStreamWithToolCalls(
        model: String,
        messages: List<ChatMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        systemPrompt: String? = null,
        webSearch: WebSearchPlugin? = null,
        fileParser: FileParserPlugin? = null,
        thinking: Boolean? = null,
        tools: List<Tool>? = null,
        toolChoice: ToolChoice? = null,
        parallelToolCalls: Boolean? = null
    ): Flow<ChatCompletionResponse> = flow {
        val response = client.httpClient.post("$baseUrl/chat/completion") {
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = model,
                messages = messages,
                stream = true,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                systemPrompt = systemPrompt,
                webSearch = webSearch,
                fileParser = fileParser,
                thinking = thinking,
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls
            ))
        }

        // Accumulates tool call data keyed by index.
        data class ToolCallAccumulator(
            val index: Int,
            var id: String? = null,
            var type: String? = null,
            var name: String? = null,
            val arguments: StringBuilder = StringBuilder()
        )
        val toolCallMap = mutableMapOf<Int, ToolCallAccumulator>()

        val channel: ByteReadChannel = response.body()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ")
                if (data != "[DONE]") {
                    try {
                        val chunk = Json.decodeFromString<StreamChunk>(data)
                        val delta = chunk.choices.firstOrNull()?.delta ?: continue

                        // Emit each delta text chunk directly (not accumulated)
                        delta.content?.let { emit(ChatCompletionResponse(text = it)) }

                        // Accumulate tool call chunks by index
                        delta.toolCalls?.forEach { streamToolCall ->
                            val acc = toolCallMap.getOrPut(streamToolCall.index) {
                                ToolCallAccumulator(index = streamToolCall.index)
                            }
                            streamToolCall.id?.let { acc.id = it }
                            streamToolCall.type?.let { acc.type = it }
                            streamToolCall.function?.name?.let { acc.name = it }
                            streamToolCall.function?.arguments?.let { acc.arguments.append(it) }
                        }
                    } catch (e: Exception) {
                        // Ignore parsing errors for individual chunks
                    }
                }
            }
        }

        // Emit final response with assembled tool calls (only if any were collected)
        if (toolCallMap.isNotEmpty()) {
            val finalToolCalls = toolCallMap.values
                .sortedBy { it.index }
                .mapNotNull { acc ->
                    val id = acc.id ?: return@mapNotNull null
                    val type = acc.type ?: "function"
                    val name = acc.name ?: return@mapNotNull null
                    ToolCall(
                        id = id,
                        type = type,
                        function = ToolCallFunction(
                            name = name,
                            arguments = acc.arguments.toString()
                        )
                    )
                }
            if (finalToolCalls.isNotEmpty()) {
                emit(ChatCompletionResponse(toolCalls = finalToolCalls))
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

    // ============ Embeddings ============

    /**
     * Generate embeddings for text input
     *
     * @param model Embedding model identifier (e.g., "google/gemini-embedding-001")
     * @param input Single text string to embed
     * @param encodingFormat The format to return embeddings in (FLOAT or BASE64, default FLOAT)
     * @param dimensions The number of dimensions for output embeddings (model-dependent)
     */
    suspend fun generateEmbeddings(
        model: String,
        input: String,
        encodingFormat: EmbeddingEncodingFormat? = null,
        dimensions: Int? = null
    ): EmbeddingsResponse {
        return generateEmbeddings(
            model = model,
            input = EmbeddingsInput.Single(input),
            encodingFormat = encodingFormat,
            dimensions = dimensions
        )
    }

    /**
     * Generate embeddings for multiple text inputs
     *
     * @param model Embedding model identifier (e.g., "google/gemini-embedding-001")
     * @param inputs List of text strings to embed
     * @param encodingFormat The format to return embeddings in (FLOAT or BASE64, default FLOAT)
     * @param dimensions The number of dimensions for output embeddings (model-dependent)
     */
    suspend fun generateEmbeddings(
        model: String,
        inputs: List<String>,
        encodingFormat: EmbeddingEncodingFormat? = null,
        dimensions: Int? = null
    ): EmbeddingsResponse {
        return generateEmbeddings(
            model = model,
            input = EmbeddingsInput.Multiple(inputs),
            encodingFormat = encodingFormat,
            dimensions = dimensions
        )
    }

    /**
     * Generate embeddings with EmbeddingsInput (internal)
     */
    private suspend fun generateEmbeddings(
        model: String,
        input: EmbeddingsInput,
        encodingFormat: EmbeddingEncodingFormat?,
        dimensions: Int?
    ): EmbeddingsResponse {
        val response = client.httpClient.post("$baseUrl/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(EmbeddingsRequest(
                model = model,
                input = input,
                encodingFormat = encodingFormat,
                dimensions = dimensions
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
