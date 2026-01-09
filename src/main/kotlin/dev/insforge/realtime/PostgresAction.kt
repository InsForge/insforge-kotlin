package dev.insforge.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Default JSON configuration for decoding records.
 * Configured to ignore unknown keys since realtime payloads may contain
 * additional metadata fields (e.g., "operation", "meta") that are not
 * part of the user's data model.
 */
@PublishedApi
internal val realtimeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Represents a Postgres database change event.
 *
 * Use specific subtypes to listen for specific events:
 * - [PostgresAction.Insert] - New row inserted
 * - [PostgresAction.Update] - Row updated
 * - [PostgresAction.Delete] - Row deleted
 *
 * Or use [PostgresAction] directly to listen for all events.
 */
sealed interface PostgresAction {

    /**
     * The schema where the change occurred
     */
    val schema: String

    /**
     * The table where the change occurred
     */
    val table: String

    /**
     * Timestamp of the change
     */
    val commitTimestamp: String?

    /**
     * Represents an INSERT operation.
     * Contains the newly inserted record.
     */
    data class Insert(
        override val schema: String,
        override val table: String,
        override val commitTimestamp: String?,
        val record: JsonObject
    ) : PostgresAction

    /**
     * Represents an UPDATE operation.
     * Contains both the old and new record values.
     */
    data class Update(
        override val schema: String,
        override val table: String,
        override val commitTimestamp: String?,
        val record: JsonObject,
        val oldRecord: JsonObject
    ) : PostgresAction

    /**
     * Represents a DELETE operation.
     * Contains the deleted record.
     */
    data class Delete(
        override val schema: String,
        override val table: String,
        override val commitTimestamp: String?,
        val oldRecord: JsonObject
    ) : PostgresAction
}

/**
 * Decode the record to a specific type.
 *
 * Example:
 * ```kotlin
 * @Serializable
 * data class Message(val id: Int, val content: String)
 *
 * channel.postgresChangeFlow<PostgresAction.Insert>("public") {
 *     table = "messages"
 * }.collect { action ->
 *     val message = action.decodeRecord<Message>()
 *     println("New message: $message")
 * }
 * ```
 */
inline fun <reified T> PostgresAction.Insert.decodeRecord(): T {
    return realtimeJson.decodeFromJsonElement(
        kotlinx.serialization.serializer<T>(),
        record
    )
}

/**
 * Decode the record to a specific type for UPDATE action.
 */
inline fun <reified T> PostgresAction.Update.decodeRecord(): T {
    return realtimeJson.decodeFromJsonElement(
        kotlinx.serialization.serializer<T>(),
        record
    )
}

/**
 * Decode the old record to a specific type for UPDATE action.
 */
inline fun <reified T> PostgresAction.Update.decodeOldRecord(): T {
    return realtimeJson.decodeFromJsonElement(
        kotlinx.serialization.serializer<T>(),
        oldRecord
    )
}

/**
 * Decode the old record to a specific type for DELETE action.
 */
inline fun <reified T> PostgresAction.Delete.decodeOldRecord(): T {
    return realtimeJson.decodeFromJsonElement(
        kotlinx.serialization.serializer<T>(),
        oldRecord
    )
}

/**
 * Helper to decode record with custom JSON configuration
 */
inline fun <reified T> PostgresAction.Insert.decodeRecord(json: kotlinx.serialization.json.Json): T {
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), record)
}

/**
 * Helper to decode record with custom JSON configuration for UPDATE action
 */
inline fun <reified T> PostgresAction.Update.decodeRecord(json: kotlinx.serialization.json.Json): T {
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), record)
}

/**
 * Helper to decode old record with custom JSON configuration for UPDATE action
 */
inline fun <reified T> PostgresAction.Update.decodeOldRecord(json: kotlinx.serialization.json.Json): T {
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), oldRecord)
}

/**
 * Helper to decode old record with custom JSON configuration for DELETE action
 */
inline fun <reified T> PostgresAction.Delete.decodeOldRecord(json: kotlinx.serialization.json.Json): T {
    return json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), oldRecord)
}
