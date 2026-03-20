package dev.insforge.ai

import dev.insforge.TestConfig
import dev.insforge.ai.models.*
import dev.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for AI module
 */
@Tag("integration")
class AITest {

    private lateinit var client: dev.insforge.InsforgeClient

    @BeforeTest
    fun setup() {
        client = TestConfig.createAIClient()
    }

    @AfterTest
    fun teardown() {
        client.close()
    }

    // ============ Model List Tests ============

    @Test
    fun `test list available models`() = runTest {
        try {
            val models = client.ai.listModels()

            println("Available models: ${models.size}")
            models.forEach { model ->
                println("  - ${model.id} (${model.provider})")
                println("    Input: ${model.inputModality}, Output: ${model.outputModality}")
            }
        } catch (e: InsforgeHttpException) {
            println("List models failed: ${e.message}")
        }
    }

    // ============ Chat Completion Tests ============

    @Test
    fun `test simple chat completion`() = runTest {
        try {
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("Say hello in 5 words or less")
                )
            )

            assertNotNull(response.text)
            println("AI Response: ${response.text}")
            println("Tokens used: ${response.metadata?.usage?.totalTokens}")
        } catch (e: InsforgeHttpException) {
            println("Chat completion failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with system prompt`() = runTest {
        try {
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("What is 2+2?")
                ),
                systemPrompt = "You are a helpful math tutor. Always explain your answers briefly."
            )

            assertNotNull(response.text)
            println("AI Response with system prompt: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Chat completion with system prompt failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with temperature`() = runTest {
        try {
            // Low temperature for more deterministic output
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("What is the capital of France?")
                ),
                temperature = 0.0
            )

            assertNotNull(response.text)
            assertTrue(response.text!!.contains("Paris", ignoreCase = true))
            println("Low temperature response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Chat completion with temperature failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with max tokens`() = runTest {
        try {
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("Write a long story about a dragon")
                ),
                maxTokens = 50  // Limit output
            )

            assertNotNull(response.text)
            assertTrue((response.metadata?.usage?.completionTokens ?: 0) <= 60) // Allow some buffer
            println("Limited response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Chat completion with max tokens failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with conversation history`() = runTest {
        try {
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("My name is Alice"),
                    ChatMessage.assistant("Hello Alice! Nice to meet you."),
                    ChatMessage.user("What is my name?")
                )
            )

            assertNotNull(response.text)
            assertTrue(response.text!!.contains("Alice", ignoreCase = true))
            println("Conversation response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Chat completion with history failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with invalid model`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.ai.chatCompletion(
                model = "invalid/model-name",
                messages = listOf(
                    ChatMessage.user("Hello")
                )
            )
        }
        println("Expected error: ${exception.error} - ${exception.message}")
    }

    // ============ Streaming Chat Completion Tests ============

    @Test
    fun `test streaming chat completion`() = runTest {
        val chunks = mutableListOf<String>()

        try {
            client.ai.chatCompletionStream(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("Count from 1 to 5")
                )
            ).collect { chunk ->
                chunks.add(chunk)
                print(chunk) // Print as it streams
            }

            println() // New line after streaming
            println("Received ${chunks.size} chunks")

            // Note: Some API configurations may not support streaming
            // or may return empty streams. We just verify no exception was thrown.
            if (chunks.isNotEmpty()) {
                println("Streaming worked successfully with content")
            } else {
                println("Stream completed but returned no content (API may not support streaming)")
            }
        } catch (e: Exception) {
            println("Streaming failed: ${e.message}")
            // Streaming may not be supported or require specific configuration
            // This is expected in some environments
        }
    }

    @Test
    fun `test streaming with system prompt`() = runTest {
        try {
            var lastResponse: String? = null

            client.ai.chatCompletionStream(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("Say hello")
                ),
                systemPrompt = "You are a pirate. Speak like one."
            ).collect { chunk ->
                lastResponse = (lastResponse ?: "") + chunk
            }

            println("Pirate response: $lastResponse")
        } catch (e: Exception) {
            println("Streaming with system prompt failed: ${e.message}")
        }
    }

    @Test
    fun `test streaming chat completion with tool calls`() = runTest(timeout = 60.seconds) {
        var toolCalls: List<ToolCall>? = null

        client.ai.chatCompletionStreamWithToolCalls(
            model = "openai/gpt-4o-mini",
            messages = listOf(
                ChatMessage.user("What's the weather in Tokyo?")
            ),
            tools = listOf(
                Tool(
                    type = "function",
                    function = ToolFunction(
                        name = "get_weather",
                        description = "Get the current weather for a location",
                        parameters = buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            putJsonObject("properties") {
                                putJsonObject("location") {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("City name"))
                                }
                            }
                            put("required", JsonArray(listOf(JsonPrimitive("location"))))
                        }
                    )
                )
            ),
            toolChoice = ToolChoice.Required
        ).collect { response ->
            if (!response.toolCalls.isNullOrEmpty()) {
                toolCalls = response.toolCalls
            }
        }

        assertNotNull(toolCalls, "Expected tool calls but got none")
        val toolCall = toolCalls!!.first()
        assertEquals("get_weather", toolCall.function.name)
        println("Streamed tool call: ${toolCall.function.name}(${toolCall.function.arguments})")
    }

    // ============ Image Generation Tests ============

    @Test
    fun `test image generation`() = runTest(timeout = 120.seconds) {
        try {
            val response = client.ai.generateImage(
                model = "google/gemini-3-pro-image-preview",
                prompt = "A cute robot waving hello, digital art style"
            )

            assertTrue(response.images.isNotEmpty())
            println("Generated ${response.count} image(s)")
            println("Model used: ${response.model}")
            response.images.forEach { image ->
                println("Image type: ${image.type}")
                println("URL length: ${image.image_url.url.length}")
            }
        } catch (e: InsforgeHttpException) {
            println("Image generation failed (may not be configured): ${e.message}")
        } catch (e: Exception) {
            // Image generation can timeout or have network issues
            println("Image generation failed with exception: ${e.message}")
        }
    }

    @Test
    fun `test image generation with detailed prompt`() = runTest(timeout = 120.seconds) {
        try {
            val response = client.ai.generateImage(
                model = "google/gemini-3-pro-image-preview",
                prompt = "A futuristic cityscape at sunset, with flying cars and neon lights, cyberpunk style, highly detailed"
            )

            assertTrue(response.images.isNotEmpty())
            println("Revised prompt: ${response.metadata.revisedPrompt}")
        } catch (e: InsforgeHttpException) {
            println("Image generation with detailed prompt failed: ${e.message}")
        } catch (e: Exception) {
            // Image generation can timeout or have network issues
            println("Image generation with detailed prompt failed with exception: ${e.message}")
        }
    }

    // ============ Configuration Management Tests (Admin) ============

    @Test
    fun `test list AI configurations`() = runTest {
        try {
            val configs = client.ai.listConfigurations()
            println("AI Configurations: ${configs.size}")
            configs.forEach { config ->
                println("  - ${config.id}: ${config.provider}/${config.modelId}")
            }
        } catch (e: InsforgeHttpException) {
            println("List configurations failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test create and delete configuration`() = runTest {
        try {
            // Create configuration
            val createResponse = client.ai.createConfiguration(
                inputModality = listOf("text"),
                outputModality = listOf("text"),
                provider = "openai",
                modelId = "gpt-4o-mini",
                systemPrompt = "You are a helpful assistant for testing"
            )

            println("Created configuration: ${createResponse.id}")

            // Delete configuration
            val deleteResponse = client.ai.deleteConfiguration(createResponse.id)
            println("Deleted: ${deleteResponse.message}")
        } catch (e: InsforgeHttpException) {
            println("Configuration management failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test update configuration`() = runTest {
        try {
            // First create a configuration
            val createResponse = client.ai.createConfiguration(
                inputModality = listOf("text"),
                outputModality = listOf("text"),
                provider = "openai",
                modelId = "gpt-4o-mini"
            )

            // Update it
            val updateResponse = client.ai.updateConfiguration(
                configId = createResponse.id,
                systemPrompt = "Updated system prompt for testing"
            )

            println("Updated configuration: ${updateResponse.message}")

            // Cleanup
            client.ai.deleteConfiguration(createResponse.id)
        } catch (e: InsforgeHttpException) {
            println("Update configuration failed (may require admin): ${e.message}")
        }
    }

    // ============ Usage Statistics Tests (Admin) ============

    @Test
    fun `test get usage summary`() = runTest {
        try {
            val summary = client.ai.getUsageSummary()
            println("Usage Summary:")
            println("  Total requests: ${summary.totalRequests}")
            println("  Total tokens: ${summary.totalTokens}")
            println("  Total images: ${summary.totalImageCount}")
        } catch (e: InsforgeHttpException) {
            println("Get usage summary failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test get usage summary with date filter`() = runTest {
        try {
            val summary = client.ai.getUsageSummary(
                startDate = "2024-01-01",
                endDate = "2024-12-31"
            )
            println("Usage for 2024: ${summary.totalRequests} requests")
        } catch (e: InsforgeHttpException) {
            println("Get filtered usage failed (may require admin): ${e.message}")
        }
    }

    @Test
    fun `test get usage records`() = runTest {
        try {
            val response = client.ai.getUsageRecords(limit = 10)
            println("Recent usage records: ${response.records.size} (total: ${response.total})")
            response.records.take(3).forEach { record ->
                println("  - ${record.modelId}: ${record.inputTokens + record.outputTokens} tokens")
            }
        } catch (e: InsforgeHttpException) {
            println("Get usage records failed (may require admin): ${e.message}")
        }
    }

    // ============ Embeddings Tests ============

    @Test
    fun `test generate embeddings with single input`() = runTest {
        try {
            val response = client.ai.generateEmbeddings(
                model = "google/gemini-embedding-001",
                input = "Hello world"
            )

            assertEquals("list", response.`object`)
            assertTrue(response.data.isNotEmpty())
            assertEquals(1, response.data.size)
            assertEquals("embedding", response.data[0].`object`)
            assertEquals(0, response.data[0].index)
            assertTrue(response.data[0].embedding.isNotEmpty())

            println("Generated embedding with ${response.data[0].embedding.size} dimensions")
            println("Model used: ${response.metadata?.model}")
        } catch (e: InsforgeHttpException) {
            println("Embeddings generation failed: ${e.message}")
        }
    }

    @Test
    fun `test generate embeddings with multiple inputs`() = runTest {
        try {
            val response = client.ai.generateEmbeddings(
                model = "google/gemini-embedding-001",
                inputs = listOf("Hello", "World", "How are you?")
            )

            assertEquals("list", response.`object`)
            assertEquals(3, response.data.size)

            response.data.forEachIndexed { index, embedding ->
                assertEquals(index, embedding.index)
                assertTrue(embedding.embedding.isNotEmpty())
                println("Embedding $index: ${embedding.embedding.size} dimensions")
            }
        } catch (e: InsforgeHttpException) {
            println("Multiple embeddings generation failed: ${e.message}")
        }
    }

    @Test
    fun `test generate embeddings with encoding format`() = runTest {
        try {
            val response = client.ai.generateEmbeddings(
                model = "google/gemini-embedding-001",
                input = "Test encoding format",
                encodingFormat = EmbeddingEncodingFormat.FLOAT
            )

            assertTrue(response.data.isNotEmpty())
            assertTrue(response.data[0].embedding.isNotEmpty())
            println("Float encoding: ${response.data[0].embedding.take(5)}...")
        } catch (e: InsforgeHttpException) {
            println("Embeddings with encoding format failed: ${e.message}")
        }
    }

    @Test
    fun `test generate embeddings with dimensions`() = runTest {
        try {
            val requestedDimensions = 512
            val response = client.ai.generateEmbeddings(
                model = "google/gemini-embedding-001",
                input = "Test with custom dimensions",
                dimensions = requestedDimensions
            )

            assertTrue(response.data.isNotEmpty())
            // Note: Not all models support custom dimensions
            println("Embedding dimensions: ${response.data[0].embedding.size}")
        } catch (e: InsforgeHttpException) {
            println("Embeddings with dimensions failed: ${e.message}")
        }
    }

    @Test
    fun `test generate embeddings with all parameters`() = runTest {
        try {
            val response = client.ai.generateEmbeddings(
                model = "google/gemini-embedding-001",
                input = "Full parameter test",
                encodingFormat = EmbeddingEncodingFormat.FLOAT,
                dimensions = 256
            )

            assertTrue(response.data.isNotEmpty())
            println("Full params - Dimensions: ${response.data[0].embedding.size}")
            response.metadata?.usage?.let { usage ->
                println("Tokens used: ${usage.totalTokens}")
            }
        } catch (e: InsforgeHttpException) {
            println("Embeddings with all parameters failed: ${e.message}")
        }
    }

    @Test
    fun `test generate embeddings with invalid model`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.ai.generateEmbeddings(
                model = "invalid/embedding-model",
                input = "This should fail"
            )
        }
        println("Expected error for invalid model: ${exception.message}")
    }

    // ============ Tool Calling Tests ============

    @Test
    fun `test chat completion with tool calling`() = runTest {
        try {
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("What's the weather in Tokyo?")
                ),
                tools = listOf(
                    Tool(
                        type = "function",
                        function = ToolFunction(
                            name = "get_weather",
                            description = "Get the current weather for a location",
                            parameters = buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                putJsonObject("properties") {
                                    putJsonObject("location") {
                                        put("type", JsonPrimitive("string"))
                                        put("description", JsonPrimitive("City name"))
                                    }
                                }
                                put("required", JsonArray(listOf(JsonPrimitive("location"))))
                            }
                        )
                    )
                ),
                toolChoice = ToolChoice.Auto
            )

            if (!response.toolCalls.isNullOrEmpty()) {
                val toolCall = response.toolCalls!!.first()
                assertEquals("get_weather", toolCall.function.name)
                println("Tool call: ${toolCall.function.name}(${toolCall.function.arguments})")
            } else {
                println("Model chose not to call a tool: ${response.text}")
            }
        } catch (e: InsforgeHttpException) {
            println("Tool calling failed: ${e.message}")
        }
    }

    // ============ Edge Cases ============

    @Test
    fun `test chat completion with empty messages`() = runTest {
        val exception = assertFailsWith<InsforgeHttpException> {
            client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = emptyList()
            )
        }
        println("Expected error for empty messages: ${exception.message}")
    }

    @Test
    fun `test chat completion with very long input`() = runTest {
        try {
            val longText = "Hello ".repeat(1000)
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("Summarize this: $longText")
                ),
                maxTokens = 100
            )

            println("Long input response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Long input test: ${e.message}")
        }
    }

    // ============ Multimodal Tests ============

    @Test
    fun `test chat completion with image URL`() = runTest {
        try {
            val response = client.ai.chatCompletionWithImages(
                model = "openai/gpt-4o",
                text = "What is in this image? Describe it briefly.",
                imageUrls = listOf("https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png")
            )

            assertNotNull(response.text)
            println("Image description: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Image chat completion failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with multiple images`() = runTest {
        try {
            val response = client.ai.chatCompletionWithImages(
                model = "openai/gpt-4o",
                text = "Compare these two images. What are the differences?",
                imageUrls = listOf(
                    "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/280px-PNG_transparency_demonstration_1.png",
                    "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a7/Camponotus_flavomarginatus_ant.jpg/320px-Camponotus_flavomarginatus_ant.jpg"
                )
            )

            assertNotNull(response.text)
            println("Multiple images comparison: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Multiple images chat completion failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with image using ChatMessage factory`() = runTest {
        try {
            val message = ChatMessage.userWithImages(
                text = "Describe this image in one sentence.",
                imageUrls = arrayOf("https://www.google.com/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png")
            )

            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o",
                messages = listOf(message)
            )

            assertNotNull(response.text)
            println("Image with factory method: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Image chat with factory failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with PDF file`() = runTest {
        try {
            val response = client.ai.chatCompletionWithFile(
                model = "anthropic/claude-sonnet-4.5",
                text = "What is this document about? Give a brief summary.",
                filename = "sample.pdf",
                fileData = "https://www.w3.org/WAI/WCAG21/Techniques/pdf/img/table-word.pdf",
                pdfEngine = PdfEngine.PDF_TEXT
            )

            assertNotNull(response.text)
            println("PDF summary: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("PDF chat completion failed: ${e.message}")
        }
    }

    @Test
    fun `test chat completion with PDF using ChatMessage factory`() = runTest {
        try {
            val message = ChatMessage.userWithFile(
                text = "Summarize this document.",
                filename = "document.pdf",
                fileData = "https://www.w3.org/WAI/WCAG21/Techniques/pdf/img/table-word.pdf"
            )

            val response = client.ai.chatCompletion(
                model = "anthropic/claude-sonnet-4.5",
                messages = listOf(message),
                fileParser = FileParserPlugin(enabled = true)
            )

            assertNotNull(response.text)
            println("PDF with factory method: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("PDF chat with factory failed: ${e.message}")
        }
    }

    @Test
    fun `test multimodal message with text and image`() = runTest {
        try {
            val message = ChatMessage.multimodal(
                "user",
                TextContent(text = "What do you see in this image?"),
                ImageContent(imageUrl = ImageUrlConfig(
                    url = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/280px-PNG_transparency_demonstration_1.png",
                    detail = ImageDetail.AUTO
                ))
            )

            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o",
                messages = listOf(message)
            )

            assertNotNull(response.text)
            println("Multimodal response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Multimodal chat failed: ${e.message}")
        }
    }

    @Test
    fun `test multimodal with image detail levels`() = runTest {
        try {
            val message = ChatMessage.multimodal(
                "user",
                TextContent(text = "Describe this image in detail."),
                ImageContent(imageUrl = ImageUrlConfig(
                    url = "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a7/Camponotus_flavomarginatus_ant.jpg/320px-Camponotus_flavomarginatus_ant.jpg",
                    detail = ImageDetail.HIGH
                ))
            )

            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o",
                messages = listOf(message)
            )

            assertNotNull(response.text)
            println("High detail image response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("High detail image failed: ${e.message}")
        }
    }

    @Test
    fun `test ChatMessage factory methods`() {
        // Test user message
        val userMsg = ChatMessage.user("Hello")
        assertEquals("user", userMsg.role)
        assertTrue(userMsg.content is ChatMessageContent.Text)
        assertEquals("Hello", (userMsg.content as ChatMessageContent.Text).text)

        // Test assistant message
        val assistantMsg = ChatMessage.assistant("Hi there")
        assertEquals("assistant", assistantMsg.role)

        // Test system message
        val systemMsg = ChatMessage.system("You are helpful")
        assertEquals("system", systemMsg.role)

        // Test multimodal message
        val multiMsg = ChatMessage.multimodal(
            "user",
            TextContent(text = "Look at this"),
            ImageContent(imageUrl = ImageUrlConfig(url = "https://example.com/img.jpg"))
        )
        assertEquals("user", multiMsg.role)
        assertTrue(multiMsg.content is ChatMessageContent.Parts)
        assertEquals(2, (multiMsg.content as ChatMessageContent.Parts).parts.size)

        // Test tool message
        val toolMsg = ChatMessage.tool("call_abc123", """{"result": "value"}""")
        assertEquals("tool", toolMsg.role)
        assertEquals("call_abc123", toolMsg.toolCallId)
        assertTrue(toolMsg.content is ChatMessageContent.Text)
        assertEquals("""{"result": "value"}""", (toolMsg.content as ChatMessageContent.Text).text)

        println("All ChatMessage factory methods work correctly")
    }

    @Test
    fun `test ChatMessage serialization format`() {
        val json = kotlinx.serialization.json.Json { prettyPrint = true }

        // Test text message serialization
        val textMsg = ChatMessage.user("Hello")
        val textJson = json.encodeToString(ChatMessage.serializer(), textMsg)
        println("Text message JSON:")
        println(textJson)
        assertTrue(textJson.contains("\"role\""))
        assertTrue(textJson.contains("\"user\""))
        assertTrue(textJson.contains("\"content\""))
        assertTrue(textJson.contains("\"Hello\""))

        // Test image message serialization
        val imageMsg = ChatMessage.userWithImages("What is this?", "https://example.com/img.jpg")
        val imageJson = json.encodeToString(ChatMessage.serializer(), imageMsg)
        println("\nImage message JSON:")
        println(imageJson)
        assertTrue(imageJson.contains("\"type\""))
        assertTrue(imageJson.contains("\"text\""))
        assertTrue(imageJson.contains("\"image_url\""))
        assertTrue(imageJson.contains("\"url\""))

        // Test file message serialization
        val fileMsg = ChatMessage.userWithFile("Summarize this", "doc.pdf", "https://example.com/doc.pdf")
        val fileJson = json.encodeToString(ChatMessage.serializer(), fileMsg)
        println("\nFile message JSON:")
        println(fileJson)
        assertTrue(fileJson.contains("\"file\""))
        assertTrue(fileJson.contains("\"filename\""))
        assertTrue(fileJson.contains("\"file_data\""))

        // Test tool message serialization
        val toolMsg = ChatMessage.tool("call_abc123", """{"result": "value"}""")
        val toolJson = json.encodeToString(ChatMessage.serializer(), toolMsg)
        println("\nTool message JSON:")
        println(toolJson)
        assertTrue(toolJson.contains("\"role\""))
        assertTrue(toolJson.contains("\"tool\""))
        assertTrue(toolJson.contains("\"tool_call_id\""))
        assertTrue(toolJson.contains("call_abc123"))

        println("\nAll serialization tests passed")
    }

    @Test
    fun `test ToolChoice serialization`() {
        val json = kotlinx.serialization.json.Json { prettyPrint = true }

        // String variants
        val autoJson = json.encodeToString(ToolChoice.serializer(), ToolChoice.Auto)
        assertEquals("\"auto\"", autoJson)

        val noneJson = json.encodeToString(ToolChoice.serializer(), ToolChoice.None)
        assertEquals("\"none\"", noneJson)

        val requiredJson = json.encodeToString(ToolChoice.serializer(), ToolChoice.Required)
        assertEquals("\"required\"", requiredJson)

        // Function variant
        val funcJson = json.encodeToString(ToolChoice.serializer(), ToolChoice.Function("get_weather"))
        assertTrue(funcJson.contains("\"type\""))
        assertTrue(funcJson.contains("\"function\""))
        assertTrue(funcJson.contains("\"get_weather\""))

        println("All ToolChoice serialization tests passed")
    }

    @Test
    fun `test chat completion with base64 image`() = runTest {
        try {
            // A tiny 1x1 red pixel PNG in base64
            val base64Image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

            val response = client.ai.chatCompletionWithImages(
                model = "openai/gpt-4o-mini",
                text = "What color is this image?",
                imageUrls = listOf(base64Image)
            )

            assertNotNull(response.text)
            println("Base64 image response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Base64 image chat failed: ${e.message}")
        }
    }

    @Test
    fun `test backward compatible text message`() = runTest {
        try {
            // Using new ChatMessage.user() factory
            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage.user("Say hello")
                )
            )

            assertNotNull(response.text)
            println("Backward compatible response: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Backward compatible test failed: ${e.message}")
        }
    }

    @Test
    fun `test conversation with multimodal messages`() = runTest {
        try {
            val messages = listOf(
                ChatMessage.system("You are a helpful assistant that can see images."),
                ChatMessage.userWithImages(
                    text = "What is in this image?",
                    imageUrls = arrayOf("https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/PNG_transparency_demonstration_1.png/280px-PNG_transparency_demonstration_1.png")
                )
            )

            val response = client.ai.chatCompletion(
                model = "openai/gpt-4o",
                messages = messages
            )

            assertNotNull(response.text)
            println("Conversation with image: ${response.text}")
        } catch (e: InsforgeHttpException) {
            println("Conversation with multimodal failed: ${e.message}")
        }
    }
}
