package io.insforge.realtime

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/**
 * Implementation of InsforgeChannel
 */
internal class InsforgeChannelImpl(
    override val topic: String,
    private val realtime: Realtime,
    private val broadcastConfig: BroadcastConfig,
    private val presenceConfig: PresenceConfig
) : InsforgeChannel {

    private val _status = MutableStateFlow(InsforgeChannel.Status.UNSUBSCRIBED)
    override val status: StateFlow<InsforgeChannel.Status> = _status.asStateFlow()

    internal val callbackManager = CallbackManager()
    private val postgresChangeConfigs = mutableListOf<PostgresChangeConfig>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Event listener references for cleanup
    private var insertListener: Realtime.EventCallback<io.insforge.realtime.models.SocketMessage>? = null
    private var updateListener: Realtime.EventCallback<io.insforge.realtime.models.SocketMessage>? = null
    private var deleteListener: Realtime.EventCallback<io.insforge.realtime.models.SocketMessage>? = null

    // ============ Subscription ============

    override suspend fun subscribe(blockUntilSubscribed: Boolean) {
        if (_status.value == InsforgeChannel.Status.SUBSCRIBED) {
            return
        }

        _status.value = InsforgeChannel.Status.SUBSCRIBING

        try {
            // Use low-level subscribe API (Socket.IO pub/sub)
            val response = realtime.subscribe(topic)

            if (response.ok) {
                // Register event listeners for postgres changes
                setupEventListeners()

                // Register this channel with the realtime instance
                realtime.registerChannel(this)

                _status.value = InsforgeChannel.Status.SUBSCRIBED
            } else {
                _status.value = InsforgeChannel.Status.UNSUBSCRIBED
                throw Exception("Subscribe failed: ${response.error?.message ?: "Unknown error"}")
            }
        } catch (e: Exception) {
            _status.value = InsforgeChannel.Status.UNSUBSCRIBED
            throw e
        }
    }

    /**
     * Setup event listeners for INSERT/UPDATE/DELETE events
     */
    private fun setupEventListeners() {
        // Listen for INSERT events
        insertListener = Realtime.EventCallback<io.insforge.realtime.models.SocketMessage> { message ->
            message?.let { handleSocketMessage("INSERT", it) }
        }
        realtime.on("INSERT", insertListener!!)

        // Listen for UPDATE events
        updateListener = Realtime.EventCallback<io.insforge.realtime.models.SocketMessage> { message ->
            message?.let { handleSocketMessage("UPDATE", it) }
        }
        realtime.on("UPDATE", updateListener!!)

        // Listen for DELETE events
        deleteListener = Realtime.EventCallback<io.insforge.realtime.models.SocketMessage> { message ->
            message?.let { handleSocketMessage("DELETE", it) }
        }
        realtime.on("DELETE", deleteListener!!)
    }

    /**
     * Handle incoming socket message and convert to PostgresAction
     */
    private fun handleSocketMessage(eventType: String, message: io.insforge.realtime.models.SocketMessage) {
        // Check if this message is for our channel
        val messageChannel = message.channel
        if (messageChannel != null && !messageChannel.endsWith(topic)) {
            return
        }

        val payload = message.payload

        // Extract fields from payload
        val schema = "public" // Default schema
        val table = topic // Use topic as table name
        val timestamp = payload["timestamp"]?.jsonPrimitive?.contentOrNull

        val action = when (eventType) {
            "INSERT" -> {
                PostgresAction.Insert(schema, table, timestamp, payload)
            }
            "UPDATE" -> {
                val oldRecord = payload["old_record"]?.jsonObject ?: JsonObject(emptyMap())
                PostgresAction.Update(schema, table, timestamp, payload, oldRecord)
            }
            "DELETE" -> {
                PostgresAction.Delete(schema, table, timestamp, payload)
            }
            else -> return
        }

        callbackManager.triggerPostgresChange(action)
    }

    /**
     * Remove event listeners
     */
    private fun removeEventListeners() {
        insertListener?.let { realtime.off("INSERT", it) }
        updateListener?.let { realtime.off("UPDATE", it) }
        deleteListener?.let { realtime.off("DELETE", it) }
        insertListener = null
        updateListener = null
        deleteListener = null
    }

    override suspend fun unsubscribe() {
        if (_status.value == InsforgeChannel.Status.UNSUBSCRIBED) {
            return
        }

        _status.value = InsforgeChannel.Status.UNSUBSCRIBING

        try {
            // Remove event listeners
            removeEventListeners()

            // Use low-level unsubscribe API
            realtime.unsubscribe(topic)

            // Unregister from realtime
            realtime.unregisterChannel(this)

            _status.value = InsforgeChannel.Status.UNSUBSCRIBED
            callbackManager.clear()
        } catch (e: Exception) {
            _status.value = InsforgeChannel.Status.SUBSCRIBED
            throw e
        }
    }

    // ============ Broadcast ============

    override suspend fun broadcast(event: String, payload: JsonObject) {
        val message = buildJsonObject {
            put("type", "broadcast")
            put("event", event)
            put("payload", payload)
        }

        if (_status.value == InsforgeChannel.Status.SUBSCRIBED) {
            // Send via WebSocket
            realtime.sendChannelMessage(
                topic = topic,
                event = "broadcast",
                payload = message
            )

            if (broadcastConfig.acknowledgeBroadcasts) {
                // Wait for acknowledgment (simplified - actual implementation would track refs)
                delay(100)
            }
        } else {
            // Send via HTTP REST API
            realtime.broadcastViaHttp(topic, event, payload)
        }
    }

    // ============ Presence (Internal - Not Yet Public) ============

    /**
     * Track presence for this client (internal use only).
     */
    internal suspend fun track(payload: JsonObject) {
        val message = buildJsonObject {
            put("type", "presence")
            put("event", "track")
            put("payload", payload)
        }

        realtime.sendChannelMessage(
            topic = topic,
            event = "presence",
            payload = message
        )
    }

    /**
     * Stop tracking presence (internal use only).
     */
    internal suspend fun untrack() {
        val message = buildJsonObject {
            put("type", "presence")
            put("event", "untrack")
        }

        realtime.sendChannelMessage(
            topic = topic,
            event = "presence",
            payload = message
        )
    }

    /**
     * Get current presence state (internal use only).
     */
    internal suspend fun presenceState(): Map<String, List<JsonObject>> {
        // This would be implemented to return cached presence state
        // For now, return empty map
        return emptyMap()
    }

    /**
     * Get presence change flow (internal use only).
     */
    internal fun presenceChangeFlowInternal(): Flow<PresenceAction> = callbackFlow {
        val id = callbackManager.addPresenceCallback { action ->
            trySend(action)
        }
        awaitClose {
            callbackManager.removeCallbackById(id)
        }
    }

    // ============ Flow APIs ============

    override fun broadcastFlowRaw(event: String): Flow<JsonObject> = callbackFlow {
        val id = callbackManager.addBroadcastCallback(event) { payload ->
            trySend(payload)
        }
        awaitClose {
            callbackManager.removeCallbackById(id)
        }
    }

    override fun <T : PostgresAction> postgresChangeFlowRaw(
        action: KClass<T>,
        schema: String,
        filter: PostgresChangeFilter.() -> Unit
    ): Flow<T> {
        // Must be called before subscribing
        check(_status.value != InsforgeChannel.Status.SUBSCRIBED) {
            "You cannot call postgresChangeFlow after subscribing to the channel. " +
                "Setup all flows before calling subscribe()."
        }

        val event = when (action) {
            PostgresAction.Insert::class -> "INSERT"
            PostgresAction.Update::class -> "UPDATE"
            PostgresAction.Delete::class -> "DELETE"
            PostgresAction::class -> "*"
            else -> error("Unknown PostgresAction type: $action")
        }

        val filterBuilder = PostgresChangeFilter(event, schema).apply(filter)
        val config = filterBuilder.buildConfig()

        // Track this configuration for the join payload
        postgresChangeConfigs.add(config)

        return callbackFlow {
            val id = callbackManager.addPostgresCallback(config) { postgresAction ->
                if (action.isInstance(postgresAction)) {
                    @Suppress("UNCHECKED_CAST")
                    trySend(postgresAction as T)
                }
            }
            awaitClose {
                callbackManager.removeCallbackById(id)
                postgresChangeConfigs.remove(config)
            }
        }
    }

    // ============ Internal Methods ============

    /**
     * Build the join payload including all configurations
     */
    private fun buildJoinPayload(): JsonObject {
        return buildJsonObject {
            // Broadcast config
            putJsonObject("config") {
                putJsonObject("broadcast") {
                    put("ack", broadcastConfig.acknowledgeBroadcasts)
                    put("self", broadcastConfig.receiveOwnBroadcasts)
                }

                // Presence config (internal)
                putJsonObject("presence") {
                    put("key", presenceConfig.key ?: "")
                }

                // Postgres changes config
                if (postgresChangeConfigs.isNotEmpty()) {
                    putJsonArray("postgres_changes") {
                        postgresChangeConfigs.forEach { config ->
                            addJsonObject {
                                put("event", config.event)
                                put("schema", config.schema)
                                config.table?.let { put("table", it) }
                                config.filter?.let { put("filter", it) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when subscription is confirmed by server
     */
    internal fun onSubscribed() {
        _status.value = InsforgeChannel.Status.SUBSCRIBED
    }

    /**
     * Called when a message is received for this channel
     */
    internal fun onMessage(event: String, payload: JsonObject) {
        when (event) {
            "broadcast" -> handleBroadcast(payload)
            "postgres_changes" -> handlePostgresChanges(payload)
            "presence_diff" -> handlePresenceDiff(payload)
            "presence_state" -> handlePresenceState(payload)
            "phx_reply" -> handleReply(payload)
        }
    }

    private fun handleBroadcast(payload: JsonObject) {
        val event = payload["event"]?.jsonPrimitive?.contentOrNull ?: return
        val data = payload["payload"]?.jsonObject ?: return
        callbackManager.triggerBroadcast(event, data)
    }

    private fun handlePostgresChanges(payload: JsonObject) {
        val data = payload["data"] ?: payload

        val schema = data.jsonObject["schema"]?.jsonPrimitive?.contentOrNull ?: return
        val table = data.jsonObject["table"]?.jsonPrimitive?.contentOrNull ?: return
        val commitTimestamp = data.jsonObject["commit_timestamp"]?.jsonPrimitive?.contentOrNull
        val eventType = data.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: return

        val action = when (eventType.uppercase()) {
            "INSERT" -> {
                val record = data.jsonObject["record"]?.jsonObject ?: return
                PostgresAction.Insert(schema, table, commitTimestamp, record)
            }
            "UPDATE" -> {
                val record = data.jsonObject["record"]?.jsonObject ?: return
                val oldRecord = data.jsonObject["old_record"]?.jsonObject ?: JsonObject(emptyMap())
                PostgresAction.Update(schema, table, commitTimestamp, record, oldRecord)
            }
            "DELETE" -> {
                val oldRecord = data.jsonObject["old_record"]?.jsonObject ?: return
                PostgresAction.Delete(schema, table, commitTimestamp, oldRecord)
            }
            else -> return
        }

        callbackManager.triggerPostgresChange(action)
    }

    private fun handlePresenceDiff(payload: JsonObject) {
        val joins = parsePresenceMap(payload["joins"]?.jsonObject)
        val leaves = parsePresenceMap(payload["leaves"]?.jsonObject)

        if (joins.isNotEmpty() || leaves.isNotEmpty()) {
            callbackManager.triggerPresence(PresenceAction(joins, leaves))
        }
    }

    private fun handlePresenceState(payload: JsonObject) {
        // Full presence state sync - treat as all joins
        val joins = parsePresenceMap(payload)
        if (joins.isNotEmpty()) {
            callbackManager.triggerPresence(PresenceAction(joins, emptyMap()))
        }
    }

    private fun parsePresenceMap(obj: JsonObject?): Map<String, PresenceState> {
        if (obj == null) return emptyMap()

        return obj.entries.mapNotNull { (key, value) ->
            val metas = value.jsonObject["metas"]?.jsonArray?.firstOrNull()?.jsonObject
            if (metas != null) {
                val presenceRef = metas["phx_ref"]?.jsonPrimitive?.contentOrNull ?: ""
                key to PresenceState(presenceRef, metas)
            } else {
                null
            }
        }.toMap()
    }

    private fun handleReply(payload: JsonObject) {
        val status = payload["status"]?.jsonPrimitive?.contentOrNull
        if (status == "ok") {
            onSubscribed()
        }
    }

    /**
     * Clean up resources
     */
    internal fun close() {
        removeEventListeners()
        scope.cancel()
        callbackManager.clear()
    }
}
