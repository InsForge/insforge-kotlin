package dev.insforge.ai

/**
 * Configuration for the AI module
 */
class AIConfig {
    /**
     * Default model for chat completions
     */
    var defaultChatModel: String? = null

    /**
     * Default model for image generation
     */
    var defaultImageModel: String? = null

    /**
     * Default temperature for chat completions
     */
    var defaultTemperature: Double = 0.7

    /**
     * Default max tokens for completions
     */
    var defaultMaxTokens: Int? = null

    /**
     * Enable streaming by default
     */
    var enableStreamingByDefault: Boolean = false
}
