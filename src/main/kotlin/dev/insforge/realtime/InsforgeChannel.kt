package dev.insforge.realtime

import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/**
 * High-level channel interface for Insforge Realtime.
 *
 * Provides a fluent API similar to Supabase for:
 * - Broadcast messages (pub/sub)
 * - Postgres database changes
 *
 * Example usage:
 * ```kotlin
 * val channel = client.realtime.channel("room-1") {
 *     broadcast {
 *         acknowledgeBroadcasts = true
 *     }
 * }
 *
 * // Listen for broadcast messages
 * channel.broadcastFlow<Message>("chat")
 *     .onEach { println("Received: $it") }
 *     .launchIn(scope)
 *
 * // Listen for database changes
 * channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
 *     table = "messages"
 * }.onEach { println("New message: ${it.record}") }
 *   .launchIn(scope)
 *
 * // Subscribe to channel
 * channel.subscribe()
 * ```
 */
interface InsforgeChannel {

    /**
     * Unique identifier for this channel
     */
    val topic: String

    /**
     * Current subscription status
     */
    val status: StateFlow<Status>

    /**
     * Channel subscription status
     */
    enum class Status {
        UNSUBSCRIBED,
        SUBSCRIBING,
        SUBSCRIBED,
        UNSUBSCRIBING
    }

    // ============ Subscription ============

    /**
     * Subscribe to the channel.
     *
     * @param blockUntilSubscribed If true, suspends until subscription is confirmed
     */
    suspend fun subscribe(blockUntilSubscribed: Boolean = false)

    /**
     * Unsubscribe from the channel
     */
    suspend fun unsubscribe()

    // ============ Broadcast ============

    /**
     * Send a broadcast message to the channel.
     *
     * @param event Event name
     * @param payload Message payload as JsonObject
     */
    suspend fun broadcast(event: String, payload: JsonObject)

    // ============ Internal Flow APIs ============

    /**
     * Internal: Get raw broadcast flow
     */
    fun broadcastFlowRaw(event: String): Flow<JsonObject>

    /**
     * Internal: Get postgres change flow
     */
    fun <T : PostgresAction> postgresChangeFlowRaw(
        action: KClass<T>,
        schema: String,
        filter: PostgresChangeFilter.() -> Unit
    ): Flow<T>
}

// ============ Extension Functions ============

/**
 * Get a Flow of broadcast messages for a specific event.
 *
 * @param event Event name to listen for ("*" for all events)
 * @return Flow emitting broadcast payloads as JsonObject
 */
fun InsforgeChannel.broadcastFlow(event: String): Flow<JsonObject> {
    return broadcastFlowRaw(event)
}

/**
 * Get a Flow of broadcast messages for a specific event, deserialized to type T.
 *
 * @param event Event name to listen for ("*" for all events)
 * @return Flow emitting deserialized broadcast payloads
 */
inline fun <reified T> InsforgeChannel.broadcastFlow(event: String, json: Json = Json): Flow<T> {
    return broadcastFlowRaw(event).map { payload ->
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), payload)
    }
}

/**
 * Send a broadcast message to the channel with typed payload.
 *
 * @param event Event name
 * @param payload Message payload (will be serialized to JSON)
 */
suspend inline fun <reified T> InsforgeChannel.broadcast(event: String, payload: T, json: Json = Json) {
    val jsonPayload = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), payload).jsonObject
    broadcast(event, jsonPayload)
}

/**
 * Get a Flow of Postgres database changes.
 *
 * Must be called BEFORE subscribing to the channel.
 *
 * @param schema Database schema to listen to (default: "public")
 * @param filter Optional filter configuration
 * @return Flow emitting PostgresAction events
 *
 * Example:
 * ```kotlin
 * // Listen for all changes
 * channel.postgresChangeFlow<PostgresAction>(schema = "public") {
 *     table = "messages"
 * }
 *
 * // Listen for inserts only
 * channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
 *     table = "messages"
 *     filter = "user_id=eq.123"
 * }
 * ```
 */
inline fun <reified T : PostgresAction> InsforgeChannel.postgresChangeFlow(
    schema: String = "public",
    noinline filter: PostgresChangeFilter.() -> Unit = {}
): Flow<T> {
    return postgresChangeFlowRaw(T::class, schema, filter)
}

/**
 * Configuration builder for InsforgeChannel
 */
@DslMarker
annotation class ChannelDsl

@ChannelDsl
class InsforgeChannelBuilder internal constructor(
    val topic: String
) {
    internal var broadcastConfig = BroadcastConfig()
    internal var presenceConfig = PresenceConfig()

    /**
     * Configure broadcast settings
     */
    fun broadcast(configure: BroadcastConfig.() -> Unit) {
        broadcastConfig = BroadcastConfig().apply(configure)
    }

    // Presence configuration is internal for now
    // TODO: Enable when presence feature is ready
    // fun presence(configure: PresenceConfig.() -> Unit) {
    //     presenceConfig = PresenceConfig().apply(configure)
    // }
}

/**
 * Broadcast configuration
 */
@ChannelDsl
class BroadcastConfig {
    /**
     * Whether to wait for server acknowledgment when broadcasting
     */
    var acknowledgeBroadcasts: Boolean = false

    /**
     * Whether this client should receive its own broadcasts
     */
    var receiveOwnBroadcasts: Boolean = false
}

/**
 * Presence configuration (internal - not yet public)
 */
@ChannelDsl
internal class PresenceConfig {
    /**
     * Unique key to identify this client in presence
     */
    var key: String? = null
}
