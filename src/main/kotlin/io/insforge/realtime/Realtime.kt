package io.insforge.realtime

import io.insforge.InsforgeClient
import io.insforge.exceptions.InsforgeHttpException
import io.insforge.plugins.InsforgePlugin
import io.insforge.plugins.InsforgePluginProvider
import io.insforge.realtime.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Realtime module for InsForge (Socket.IO pub/sub channels)
 *
 * InsForge uses Socket.IO for realtime communication. The HTTP REST API at
 * /api/realtime/ is only for management operations (channels, messages, permissions).
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
 * // Subscribe to a channel
 * val response = client.realtime.subscribe("orders:123")
 * if (!response.ok) {
 *     println("Failed to subscribe: ${response.error}")
 * }
 *
 * // Listen for specific events
 * client.realtime.on("order_updated") { message ->
 *     println("Order updated: ${message.payload}")
 * }
 *
 * // Listen for connection events
 * client.realtime.on("connect") { println("Connected!") }
 * client.realtime.on("disconnect") { reason -> println("Disconnected: $reason") }
 *
 * // Publish a message to a channel
 * client.realtime.publish("orders:123", "status_changed", mapOf("status" to "shipped"))
 *
 * // Unsubscribe and disconnect when done
 * client.realtime.unsubscribe("orders:123")
 * client.realtime.disconnect()
 * ```
 */
class Realtime internal constructor(
    private val client: InsforgeClient,
    private val config: RealtimeConfig
) : InsforgePlugin<RealtimeConfig> {

    override val key: String = Realtime.key

    // REST API base URL for management operations
    private val baseUrl = "${client.baseURL}/api/realtime"

    // Socket.IO client
    private var socket: Socket? = null
    private var connectJob: Deferred<Unit>? = null
    private val subscribedChannels = ConcurrentHashMap.newKeySet<String>()
    private val eventListeners = ConcurrentHashMap<String, MutableSet<EventCallback<*>>>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // High-level channel management (for advanced API)
    private val channels = ConcurrentHashMap<String, InsforgeChannelImpl>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Debug logging prefix
    private val logTag = "[InsForge Realtime]"

    /**
     * Log a debug message if debug mode is enabled
     */
    private fun logDebug(message: String) {
        if (config.debug) {
            println("$logTag [DEBUG] $message")
        }
    }

    /**
     * Log an outgoing message
     */
    private fun logOutgoing(event: String, data: Any?) {
        if (config.debug) {
            println("$logTag [>>>] SEND: event='$event'")
            data?.let { println("$logTag [>>>]   data: $it") }
        }
    }

    /**
     * Log an incoming message
     */
    private fun logIncoming(event: String, data: Any?) {
        if (config.debug) {
            println("$logTag [<<<] RECV: event='$event'")
            data?.let { println("$logTag [<<<]   data: $it") }
        }
    }

    companion object : InsforgePluginProvider<RealtimeConfig, Realtime> {
        override val key: String = "realtime"
        private const val CONNECT_TIMEOUT_MS = 10000L

        override fun createConfig(configure: RealtimeConfig.() -> Unit): RealtimeConfig {
            return RealtimeConfig().apply(configure)
        }

        override fun create(client: InsforgeClient, config: RealtimeConfig): Realtime {
            return Realtime(client, config)
        }
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    // Type alias for event callbacks
    fun interface EventCallback<T> {
        fun onEvent(payload: T?)
    }

    // ============ Connection Management ============

    /**
     * Check if connected to the realtime server
     */
    val isConnected: Boolean
        get() = socket?.connected() == true

    /**
     * Get the socket ID (if connected)
     */
    val socketId: String?
        get() = socket?.id()

    /**
     * Connect to the realtime Socket.IO server.
     *
     * @return Promise that resolves when connected
     * @throws Exception if connection fails or times out
     */
    suspend fun connect() {
        // Already connected
        if (socket?.connected() == true) {
            return
        }

        // Connection already in progress, wait for it
        connectJob?.let {
            if (it.isActive) {
                it.await()
                return
            }
        }

        connectJob = scope.async {
            suspendCancellableCoroutine { continuation ->
                try {
                    _connectionState.value = ConnectionState.Connecting

                    // Get auth token
                    val token = client.getCurrentAccessToken() ?: client.anonKey

                    logDebug("Connecting to ${client.baseURL}")
                    logDebug("Auth token: ${if (token.isNotEmpty()) "${token.take(20)}..." else "(none)"}")

                    // Configure Socket.IO options
                    val options = IO.Options().apply {
                        // Transport - prefer websocket
                        transports = arrayOf("websocket")
                        // Auth configuration
                        if (token.isNotEmpty()) {
                            auth = mapOf("token" to token)
                        }
                        // Reconnection options
                        reconnection = true
                        reconnectionAttempts = 5
                        reconnectionDelay = 1000
                        reconnectionDelayMax = 5000
                        // Timeout
                        timeout = CONNECT_TIMEOUT_MS
                    }

                    logDebug("Socket.IO options: transports=${options.transports.toList()}, reconnection=${options.reconnection}")

                    // Create socket connection to base URL
                    socket = IO.socket(client.baseURL, options)

                    var initialConnection = true

                    // Setup timeout
                    val timeoutJob = scope.launch {
                        delay(CONNECT_TIMEOUT_MS)
                        if (initialConnection) {
                            initialConnection = false
                            socket?.disconnect()
                            socket = null
                            _connectionState.value = ConnectionState.Disconnected
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    Exception("Connection timeout after ${CONNECT_TIMEOUT_MS}ms")
                                )
                            }
                        }
                    }

                    socket?.apply {
                        on(Socket.EVENT_CONNECT) {
                            timeoutJob.cancel()
                            _connectionState.value = ConnectionState.Connected
                            println("$logTag Connected to Socket.IO server")
                            logDebug("Socket ID: ${id()}")

                            // Re-subscribe to channels on every connect (initial + reconnects)
                            for (channel in subscribedChannels) {
                                val subscribeData = JSONObject().apply {
                                    put("channel", channel)
                                }
                                logOutgoing("realtime:subscribe", subscribeData)
                                emit("realtime:subscribe", subscribeData)
                            }

                            notifyListeners("connect", null)

                            if (initialConnection) {
                                initialConnection = false
                                if (continuation.isActive) {
                                    continuation.resume(Unit)
                                }
                            }
                        }

                        on(Socket.EVENT_CONNECT_ERROR) { args ->
                            timeoutJob.cancel()
                            val error = args.firstOrNull()?.toString() ?: "Unknown error"
                            _connectionState.value = ConnectionState.Error(error)
                            println("$logTag Connection error: $error")
                            logDebug("Connection error details: ${args.toList()}")

                            notifyListeners("connect_error", error)

                            if (initialConnection) {
                                initialConnection = false
                                if (continuation.isActive) {
                                    continuation.resumeWithException(Exception(error))
                                }
                            }
                        }

                        on(Socket.EVENT_DISCONNECT) { args ->
                            _connectionState.value = ConnectionState.Disconnected
                            val reason = args.firstOrNull()?.toString() ?: "unknown"
                            println("$logTag Disconnected: $reason")
                            logDebug("Disconnect reason: $reason")
                            notifyListeners("disconnect", reason)
                        }

                        on("realtime:error") { args ->
                            logIncoming("realtime:error", args.firstOrNull())
                            val data = args.firstOrNull() as? JSONObject
                            val error = RealtimeError(
                                code = data?.optString("code") ?: "UNKNOWN",
                                message = data?.optString("message") ?: "Unknown error"
                            )
                            println("$logTag Error: ${error.message}")
                            notifyListeners("error", error)
                        }

                        // Listen for incoming realtime messages
                        on("realtime:message") { args ->
                            logIncoming("realtime:message", args.firstOrNull())
                            handleIncomingEvent("realtime:message", args)
                        }

                        // Catch-all listener for all incoming events
                        // This mirrors the TypeScript SDK's socket.onAny() behavior
                        onAnyIncoming { args ->
                            // args[0] is the event name, args[1:] are the actual arguments
                            if (args.isNotEmpty()) {
                                val eventName = args[0] as? String ?: return@onAnyIncoming
                                // Skip already handled events
                                if (eventName == "realtime:error") return@onAnyIncoming
                                val eventArgs = if (args.size > 1) args.sliceArray(1 until args.size) else emptyArray()
                                logIncoming(eventName, eventArgs.firstOrNull())
                                handleIncomingEvent(eventName, eventArgs)
                            }
                        }

                        // Connect
                        logDebug("Initiating socket connection...")
                        connect()
                    }

                } catch (e: Exception) {
                    _connectionState.value = ConnectionState.Error(e.message ?: "Failed to connect")
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }

        connectJob?.await()
    }

    /**
     * Disconnect from the realtime server
     */
    fun disconnect() {
        socket?.disconnect()
        socket?.off()
        socket = null
        subscribedChannels.clear()
        _connectionState.value = ConnectionState.Disconnected
    }

    // ============ Subscription ============

    /**
     * Subscribe to a channel.
     *
     * Automatically connects if not already connected.
     *
     * @param channel Channel name (e.g., "orders:123", "broadcast")
     * @return SubscribeResponse indicating success or failure
     */
    suspend fun subscribe(channel: String): SubscribeResponse {
        logDebug("subscribe() called for channel: $channel")

        // Already subscribed, return success
        if (subscribedChannels.contains(channel)) {
            logDebug("Already subscribed to '$channel', returning cached success")
            return SubscribeResponse(ok = true, channel = channel)
        }

        // Auto-connect if not connected
        if (socket?.connected() != true) {
            logDebug("Not connected, initiating connection...")
            try {
                connect()
            } catch (e: Exception) {
                logDebug("Connection failed: ${e.message}")
                return SubscribeResponse(
                    ok = false,
                    channel = channel,
                    error = RealtimeError("CONNECTION_FAILED", e.message ?: "Connection failed")
                )
            }
        }

        return suspendCancellableCoroutine { continuation ->
            val subscribeData = JSONObject().apply {
                put("channel", channel)
            }
            logOutgoing("realtime:subscribe", subscribeData)

            socket?.emit("realtime:subscribe", arrayOf(subscribeData)) { args ->
                val response = args.firstOrNull() as? JSONObject
                logIncoming("realtime:subscribe (ack)", response)

                val ok = response?.optBoolean("ok", false) ?: false

                if (ok) {
                    subscribedChannels.add(channel)
                    logDebug("Successfully subscribed to '$channel'")
                } else {
                    logDebug("Subscribe to '$channel' failed")
                }

                val result = SubscribeResponse(
                    ok = ok,
                    channel = channel,
                    error = if (!ok) {
                        val errorObj = response?.optJSONObject("error")
                        RealtimeError(
                            code = errorObj?.optString("code") ?: "SUBSCRIBE_FAILED",
                            message = errorObj?.optString("message") ?: "Subscription failed"
                        )
                    } else null
                )

                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
    }

    /**
     * Unsubscribe from a channel (fire-and-forget)
     *
     * @param channel Channel name to unsubscribe from
     */
    fun unsubscribe(channel: String) {
        logDebug("unsubscribe() called for channel: $channel")
        subscribedChannels.remove(channel)

        if (socket?.connected() == true) {
            val unsubscribeData = JSONObject().apply {
                put("channel", channel)
            }
            logOutgoing("realtime:unsubscribe", unsubscribeData)
            socket?.emit("realtime:unsubscribe", unsubscribeData)
        } else {
            logDebug("Not connected, skipping unsubscribe emit")
        }
    }

    /**
     * Get all currently subscribed channels
     *
     * @return List of channel names
     */
    fun getSubscribedChannels(): List<String> {
        return subscribedChannels.toList()
    }

    // ============ Publishing ============

    /**
     * Publish a message to a channel
     *
     * @param channel Channel name
     * @param event Event name
     * @param payload Message payload
     * @throws IllegalStateException if not connected
     */
    fun publish(channel: String, event: String, payload: Map<String, Any>) {
        logDebug("publish() called: channel='$channel', event='$event'")

        if (socket?.connected() != true) {
            logDebug("Publish failed: not connected")
            throw IllegalStateException("Not connected to realtime server. Call connect() first.")
        }

        val publishData = JSONObject().apply {
            put("channel", channel)
            put("event", event)
            put("payload", JSONObject(payload))
        }
        logOutgoing("realtime:publish", publishData)
        socket?.emit("realtime:publish", publishData)
    }

    // ============ Event Listeners ============

    /**
     * Listen for events.
     *
     * Reserved event names:
     * - "connect" - Fired when connected to the server
     * - "connect_error" - Fired when connection fails (payload: String error message)
     * - "disconnect" - Fired when disconnected (payload: String reason)
     * - "error" - Fired when a realtime error occurs (payload: RealtimeError)
     *
     * All other events receive a SocketMessage payload with metadata.
     *
     * @param event Event name to listen for
     * @param callback Callback function when event is received
     */
    fun <T> on(event: String, callback: EventCallback<T>) {
        eventListeners.getOrPut(event) { ConcurrentHashMap.newKeySet() }.add(callback)
    }

    /**
     * Remove a listener for a specific event
     *
     * @param event Event name
     * @param callback The callback function to remove
     */
    fun <T> off(event: String, callback: EventCallback<T>) {
        eventListeners[event]?.remove(callback)
        if (eventListeners[event]?.isEmpty() == true) {
            eventListeners.remove(event)
        }
    }

    /**
     * Listen for an event only once, then automatically remove the listener
     *
     * @param event Event name to listen for
     * @param callback Callback function when event is received
     */
    fun <T> once(event: String, callback: EventCallback<T>) {
        val wrapper = object : EventCallback<T> {
            override fun onEvent(payload: T?) {
                off(event, this)
                callback.onEvent(payload)
            }
        }
        on(event, wrapper)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> notifyListeners(event: String, payload: T?) {
        eventListeners[event]?.forEach { callback ->
            try {
                (callback as EventCallback<T>).onEvent(payload)
            } catch (e: Exception) {
                println("[InsForge Realtime] Error in $event callback: ${e.message}")
            }
        }
    }

    private fun handleIncomingEvent(event: String, args: Array<out Any?>) {
        try {
            val data = args.firstOrNull() as? JSONObject ?: return

            // Parse meta object (server format: { meta: {...}, ...payload })
            val metaObj = data.optJSONObject("meta")
            val meta = if (metaObj != null) {
                SocketMessageMeta(
                    channel = metaObj.optString("channel", null),
                    messageId = metaObj.optString("messageId", ""),
                    senderType = metaObj.optString("senderType", "user"),
                    senderId = metaObj.optString("senderId", null),
                    timestamp = metaObj.optString("timestamp", "")
                )
            } else {
                // Fallback for legacy/simple format
                SocketMessageMeta(
                    channel = data.optString("channel", null),
                    messageId = data.optString("messageId", ""),
                    senderType = data.optString("senderType", "user"),
                    senderId = data.optString("senderId", null),
                    timestamp = data.optString("timestamp", "")
                )
            }

            // Extract payload - everything except meta is payload
            // For passthrough schema, payload fields are at root level alongside meta
            val payloadObj = if (metaObj != null) {
                // Remove meta from data to get payload
                val payloadData = JSONObject(data.toString())
                payloadData.remove("meta")
                payloadData
            } else {
                data.optJSONObject("payload") ?: JSONObject()
            }

            val message = SocketMessage(
                meta = meta,
                event = data.optString("event", event),
                payload = parsePayload(payloadObj)
            )

            // Notify listeners for both the raw event and the specific message event
            notifyListeners(event, message)
            if (message.event != event) {
                notifyListeners(message.event, message)
            }
        } catch (e: Exception) {
            println("[InsForge Realtime] Error handling event $event: ${e.message}")
        }
    }

    private fun parsePayload(jsonObj: JSONObject?): JsonObject {
        if (jsonObj == null) return buildJsonObject { }
        return try {
            json.parseToJsonElement(jsonObj.toString()) as? JsonObject ?: buildJsonObject { }
        } catch (e: Exception) {
            buildJsonObject { }
        }
    }

    // ============ High-Level Channel API ============

    /**
     * Create or get an existing channel (advanced API).
     *
     * @param topic Channel topic/name
     * @param configure Optional channel configuration
     * @return InsforgeChannel instance
     */
    fun channel(
        topic: String,
        configure: InsforgeChannelBuilder.() -> Unit = {}
    ): InsforgeChannel {
        return channels.getOrPut(topic) {
            val builder = InsforgeChannelBuilder(topic).apply(configure)
            InsforgeChannelImpl(
                topic = topic,
                realtime = this,
                broadcastConfig = builder.broadcastConfig,
                presenceConfig = builder.presenceConfig
            )
        }
    }

    /**
     * Remove a channel from management.
     *
     * @param topic Channel topic to remove
     */
    suspend fun removeChannel(topic: String) {
        channels.remove(topic)?.let { channel ->
            if (channel.status.value == InsforgeChannel.Status.SUBSCRIBED) {
                channel.unsubscribe()
            }
            channel.close()
        }
    }

    /**
     * Remove all channels
     */
    suspend fun removeAllChannels() {
        channels.keys.toList().forEach { removeChannel(it) }
    }

    // ============ Internal Methods for Channel Support ============

    internal fun sendChannelMessage(topic: String, event: String, payload: JsonObject) {
        publish(topic, event, payload.toMap())
    }

    internal suspend fun broadcastViaHttp(topic: String, event: String, payload: JsonObject) {
        client.httpClient.post("$baseUrl/broadcast") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("topic", topic)
                put("event", event)
                put("payload", payload)
            })
        }
    }

    internal fun registerChannel(channel: InsforgeChannelImpl) {
        channels[channel.topic] = channel
    }

    internal fun unregisterChannel(channel: InsforgeChannelImpl) {
        channels.remove(channel.topic)
    }

    // ============ REST API (Channel Management) ============

    /**
     * List all channels (admin only)
     */
    suspend fun listChannels(): List<RealtimeChannel> {
        val response = client.httpClient.get("$baseUrl/channels")
        return handleResponse(response)
    }

    /**
     * Get channel by ID
     */
    suspend fun getChannel(channelId: String): RealtimeChannel {
        val response = client.httpClient.get("$baseUrl/channels/$channelId")
        return handleResponse(response)
    }

    /**
     * Create a new channel (admin only)
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
     */
    suspend fun deleteChannel(channelId: String): DeleteChannelResponse {
        val response = client.httpClient.delete("$baseUrl/channels/$channelId")
        return handleResponse(response)
    }

    // ============ REST API (Message History) ============

    /**
     * Get message history
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

    private fun JsonObject.toMap(): Map<String, Any> {
        return this.entries.associate { (key, value) ->
            key to when {
                value is kotlinx.serialization.json.JsonPrimitive -> value.content
                value is JsonObject -> value.toMap()
                value is kotlinx.serialization.json.JsonArray -> value.map {
                    when (it) {
                        is kotlinx.serialization.json.JsonPrimitive -> it.content
                        is JsonObject -> it.toMap()
                        else -> it.toString()
                    }
                }
                else -> value.toString()
            }
        }
    }

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
            Json.decodeFromString<io.insforge.exceptions.ErrorResponse>(errorBody)
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
        channels.values.forEach { it.close() }
        channels.clear()
        scope.cancel()
        disconnect()
    }
}

/**
 * Extension property for accessing Realtime module
 */
val InsforgeClient.realtime: Realtime
    get() = plugin(Realtime.key)
