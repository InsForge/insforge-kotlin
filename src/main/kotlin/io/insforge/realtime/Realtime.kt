package io.insforge.realtime

import io.insforge.InsforgeClient
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.InsforgePluginProvider
import io.insforge.realtime.models.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Realtime module for InsForge (WebSocket pub/sub channels)
 *
 * Install this module in your Insforge client:
 * ```kotlin
 * val client = createInsforgeClient(baseURL, anonKey) {
 *     install(Realtime)
 * }
 *
 * // Connect to realtime
 * client.realtime.connect()
 *
 * // Subscribe to channel
 * client.realtime.subscribe("chat:room1") { message ->
 *     println("Received: ${message.payload}")
 * }
 *
 * // Publish message
 * client.realtime.publish("chat:room1", "message.new", mapOf("text" to "Hello"))
 * ```
 */
class Realtime internal constructor(
    private val client: InsforgeClient,
    private val config: RealtimeConfig
) : InsforgePlugin<RealtimeConfig> {

    override val key: String = Realtime.key

    private val baseUrl = "${client.baseURL}/api/realtime"
    private val wsUrl = client.baseURL.replace("https://", "wss://").replace("http://", "ws://")

    private var wsSession: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private val subscriptions = mutableMapOf<String, MutableList<suspend (RealtimeMessage) -> Unit>>()
    private val messageChannel = Channel<RealtimeMessage>(Channel.UNLIMITED)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // ============ WebSocket Connection ============

    /**
     * Connect to realtime WebSocket
     */
    suspend fun connect() {
        if (_connectionState.value is ConnectionState.Connected) {
            return // Already connected
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            connectionJob = scope.launch {
                client.httpClient.webSocket(
                    host = wsUrl.removePrefix("wss://").removePrefix("ws://").split("/")[0],
                    path = "/realtime",
                    request = {
                        header("x-api-key", client.anonKey)
                    }
                ) {
                    wsSession = this
                    _connectionState.value = ConnectionState.Connected

                    // Start message handler
                    launch { handleIncomingMessages() }

                    // Keep connection alive
                    try {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                try {
                                    val message = Json.decodeFromString<RealtimeMessage>(text)
                                    messageChannel.send(message)
                                } catch (e: Exception) {
                                    // Ignore parsing errors
                                }
                            }
                        }
                    } catch (e: Exception) {
                        _connectionState.value = ConnectionState.Error(e.message ?: "Connection error")
                    }
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Failed to connect")
            throw e
        }
    }

    /**
     * Disconnect from realtime WebSocket
     */
    suspend fun disconnect() {
        wsSession?.close()
        wsSession = null
        connectionJob?.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Subscribe to a channel
     *
     * @param channelName Channel name to subscribe to
     * @param handler Message handler callback
     */
    fun subscribe(channelName: String, handler: suspend (RealtimeMessage) -> Unit) {
        subscriptions.getOrPut(channelName) { mutableListOf() }.add(handler)

        // Send subscribe message to server
        scope.launch {
            wsSession?.send(Frame.Text(Json.encodeToString(mapOf(
                "type" to "subscribe",
                "channel" to channelName
            ))))
        }
    }

    /**
     * Unsubscribe from a channel
     *
     * @param channelName Channel name to unsubscribe from
     */
    fun unsubscribe(channelName: String) {
        subscriptions.remove(channelName)

        // Send unsubscribe message to server
        scope.launch {
            wsSession?.send(Frame.Text(Json.encodeToString(mapOf(
                "type" to "unsubscribe",
                "channel" to channelName
            ))))
        }
    }

    /**
     * Publish a message to a channel
     *
     * @param channelName Channel name
     * @param eventName Event name
     * @param payload Message payload
     */
    suspend fun publish(channelName: String, eventName: String, payload: Map<String, Any>) {
        wsSession?.send(Frame.Text(Json.encodeToString(mapOf(
            "type" to "publish",
            "channel" to channelName,
            "event" to eventName,
            "payload" to payload
        ))))
    }

    private suspend fun handleIncomingMessages() {
        for (message in messageChannel) {
            // Find matching subscriptions
            subscriptions.forEach { (pattern, handlers) ->
                if (matchesPattern(message.channelName, pattern)) {
                    handlers.forEach { handler ->
                        scope.launch {
                            try {
                                handler(message)
                            } catch (e: Exception) {
                                // Log error but don't crash
                            }
                        }
                    }
                }
            }
        }
    }

    private fun matchesPattern(channel: String, pattern: String): Boolean {
        if (pattern == channel) return true
        if (pattern.endsWith("*")) {
            val prefix = pattern.dropLast(1)
            return channel.startsWith(prefix)
        }
        return false
    }

    // ============ Channel Management (REST API) ============

    /**
     * List all channels (admin only)
     */
    suspend fun listChannels(): List<RealtimeChannel> {
        val response = client.httpClient.get("$baseUrl/channels")
        return handleResponse(response)
    }

    /**
     * Get channel by ID
     *
     * @param channelId Channel UUID
     */
    suspend fun getChannel(channelId: String): RealtimeChannel {
        val response = client.httpClient.get("$baseUrl/channels/$channelId")
        return handleResponse(response)
    }

    /**
     * Create a new channel (admin only)
     *
     * @param pattern Channel pattern (supports wildcards like "chat:*")
     * @param description Optional description
     * @param webhookUrls Optional webhook URLs
     * @param enabled Whether channel is enabled
     */
    suspend fun createChannel(
        pattern: String,
        description: String? = null,
        webhookUrls: List<String>? = null,
        enabled: Boolean = true
    ): RealtimeChannel {
        val response = client.httpClient.post("$baseUrl/channels") {
            contentType(ContentType.Application.Json)
            setBody(CreateChannelRequest(
                pattern = pattern,
                description = description,
                webhookUrls = webhookUrls,
                enabled = enabled
            ))
        }
        return handleResponse(response)
    }

    /**
     * Update a channel (admin only)
     *
     * @param channelId Channel UUID
     * @param pattern Updated pattern
     * @param description Updated description
     * @param webhookUrls Updated webhook URLs
     * @param enabled Updated enabled status
     */
    suspend fun updateChannel(
        channelId: String,
        pattern: String? = null,
        description: String? = null,
        webhookUrls: List<String>? = null,
        enabled: Boolean? = null
    ): RealtimeChannel {
        val response = client.httpClient.put("$baseUrl/channels/$channelId") {
            contentType(ContentType.Application.Json)
            setBody(UpdateChannelRequest(
                pattern = pattern,
                description = description,
                webhookUrls = webhookUrls,
                enabled = enabled
            ))
        }
        return handleResponse(response)
    }

    /**
     * Delete a channel (admin only)
     *
     * @param channelId Channel UUID
     */
    suspend fun deleteChannel(channelId: String): DeleteChannelResponse {
        val response = client.httpClient.delete("$baseUrl/channels/$channelId")
        return handleResponse(response)
    }

    // ============ Message History ============

    /**
     * Get message history
     *
     * @param channelId Optional channel ID filter
     * @param eventName Optional event name filter
     * @param limit Maximum number of messages
     * @param offset Offset for pagination
     */
    suspend fun getMessages(
        channelId: String? = null,
        eventName: String? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<RealtimeMessage> {
        val response = client.httpClient.get("$baseUrl/messages") {
            channelId?.let { parameter("channelId", it) }
            eventName?.let { parameter("eventName", it) }
            parameter("limit", limit)
            parameter("offset", offset)
        }
        return handleResponse(response)
    }

    /**
     * Get message statistics
     *
     * @param channelId Optional channel ID filter
     * @param since Optional timestamp filter
     */
    suspend fun getMessageStats(
        channelId: String? = null,
        since: String? = null
    ): MessageStats {
        val response = client.httpClient.get("$baseUrl/messages/stats") {
            channelId?.let { parameter("channelId", it) }
            since?.let { parameter("since", it) }
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
            kotlinx.serialization.json.Json.decodeFromString<io.insforge.exceptions.ErrorResponse>(errorBody)
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

    override fun close() {
        scope.cancel()
        runBlocking {
            disconnect()
        }
    }

    companion object : InsforgePluginProvider<RealtimeConfig, Realtime> {
        override val key: String = "realtime"

        override fun createConfig(configure: RealtimeConfig.() -> Unit): RealtimeConfig {
            return RealtimeConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: RealtimeConfig): Realtime {
            return Realtime(client, config)
        }
    }
}

/**
 * Extension property for accessing Realtime module
 */
val InsforgeClient.realtime: Realtime
    get() = plugin(Realtime.key)
