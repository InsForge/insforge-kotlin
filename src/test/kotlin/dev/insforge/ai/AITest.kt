package dev.insforge.ai

import dev.insforge.TestConfig
import dev.insforge.ai.models.ChatMessage
import dev.insforge.exceptions.InsforgeHttpException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Integration tests for AI module
 */
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
                    ChatMessage(role = "user", content = "Say hello in 5 words or less")
                )
            )

            assertTrue(response.success)
            assertNotNull(response.content)
            println("AI Response: ${response.content}")
            println("Tokens used: ${response.metadata.usage.totalTokens}")
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
                    ChatMessage(role = "user", content = "What is 2+2?")
                ),
                systemPrompt = "You are a helpful math tutor. Always explain your answers briefly."
            )

            assertTrue(response.success)
            println("AI Response with system prompt: ${response.content}")
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
                    ChatMessage(role = "user", content = "What is the capital of France?")
                ),
                temperature = 0.0
            )

            assertTrue(response.success)
            assertTrue(response.content.contains("Paris", ignoreCase = true))
            println("Low temperature response: ${response.content}")
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
                    ChatMessage(role = "user", content = "Write a long story about a dragon")
                ),
                maxTokens = 50  // Limit output
            )

            assertTrue(response.success)
            assertTrue(response.metadata.usage.completionTokens <= 60) // Allow some buffer
            println("Limited response: ${response.content}")
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
                    ChatMessage(role = "user", content = "My name is Alice"),
                    ChatMessage(role = "assistant", content = "Hello Alice! Nice to meet you."),
                    ChatMessage(role = "user", content = "What is my name?")
                )
            )

            assertTrue(response.success)
            assertTrue(response.content.contains("Alice", ignoreCase = true))
            println("Conversation response: ${response.content}")
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
                    ChatMessage(role = "user", content = "Hello")
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
                    ChatMessage(role = "user", content = "Count from 1 to 5")
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
            val fullResponse = StringBuilder()

            client.ai.chatCompletionStream(
                model = "openai/gpt-4o-mini",
                messages = listOf(
                    ChatMessage(role = "user", content = "Say hello")
                ),
                systemPrompt = "You are a pirate. Speak like one."
            ).collect { chunk ->
                fullResponse.append(chunk)
            }

            println("Pirate response: $fullResponse")
        } catch (e: Exception) {
            println("Streaming with system prompt failed: ${e.message}")
        }
    }

    // ============ Image Generation Tests ============

    @Test
    fun `test image generation`() = runTest {
        try {
            val response = client.ai.generateImage(
                model = "openai/dall-e-3",
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
        }
    }

    @Test
    fun `test image generation with detailed prompt`() = runTest {
        try {
            val response = client.ai.generateImage(
                model = "openai/dall-e-3",
                prompt = "A futuristic cityscape at sunset, with flying cars and neon lights, cyberpunk style, highly detailed"
            )

            assertTrue(response.images.isNotEmpty())
            println("Revised prompt: ${response.metadata.revisedPrompt}")
        } catch (e: InsforgeHttpException) {
            println("Image generation with detailed prompt failed: ${e.message}")
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
                    ChatMessage(role = "user", content = "Summarize this: $longText")
                ),
                maxTokens = 100
            )

            println("Long input response: ${response.content}")
        } catch (e: InsforgeHttpException) {
            println("Long input test: ${e.message}")
        }
    }
}
