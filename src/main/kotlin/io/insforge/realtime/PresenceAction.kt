package io.insforge.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Represents a presence change event (internal - not yet public).
 *
 * Contains information about clients that joined or left the channel.
 */
internal data class PresenceAction(
    /**
     * Map of presence keys to their data for clients that joined
     */
    val joins: Map<String, PresenceState>,

    /**
     * Map of presence keys to their data for clients that left
     */
    val leaves: Map<String, PresenceState>
)

/**
 * Presence state for a single client (internal - not yet public)
 */
internal data class PresenceState(
    /**
     * Unique reference for this presence entry
     */
    val presenceRef: String,

    /**
     * Custom payload data
     */
    val payload: JsonObject
)

/**
 * Decode joins as a specific type (internal - not yet public).
 */
internal inline fun <reified T> PresenceAction.decodeJoinsAs(): List<T> {
    return joins.values.map { state ->
        Json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), state.payload)
    }
}

/**
 * Decode leaves as a specific type (internal - not yet public).
 */
internal inline fun <reified T> PresenceAction.decodeLeavesAs(): List<T> {
    return leaves.values.map { state ->
        Json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), state.payload)
    }
}

/**
 * Decode joins with custom JSON configuration (internal - not yet public)
 */
internal inline fun <reified T> PresenceAction.decodeJoinsAs(json: Json): List<T> {
    return joins.values.map { state ->
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), state.payload)
    }
}

/**
 * Decode leaves with custom JSON configuration (internal - not yet public)
 */
internal inline fun <reified T> PresenceAction.decodeLeavesAs(json: Json): List<T> {
    return leaves.values.map { state ->
        json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), state.payload)
    }
}
