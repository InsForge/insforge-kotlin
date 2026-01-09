package dev.insforge.realtime

import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages callbacks for realtime events.
 *
 * Handles routing of:
 * - Broadcast messages
 * - Postgres change events
 * - Presence events
 */
internal class CallbackManager {

    private val broadcastCallbacks = ConcurrentHashMap<String, BroadcastCallbackEntry>()
    private val postgresCallbacks = ConcurrentHashMap<String, PostgresCallbackEntry>()
    private val presenceCallbacks = ConcurrentHashMap<String, PresenceCallbackEntry>()

    // ============ Broadcast Callbacks ============

    /**
     * Add a callback for broadcast events
     *
     * @param event Event name to listen for ("*" for all)
     * @param callback Function to call when message received
     * @return Callback ID for removal
     */
    fun addBroadcastCallback(
        event: String,
        callback: (JsonObject) -> Unit
    ): String {
        val id = UUID.randomUUID().toString()
        broadcastCallbacks[id] = BroadcastCallbackEntry(event, callback)
        return id
    }

    /**
     * Trigger broadcast callbacks for an event
     */
    fun triggerBroadcast(event: String, payload: JsonObject) {
        broadcastCallbacks.values.forEach { entry ->
            if (entry.event == "*" || entry.event == event) {
                try {
                    entry.callback(payload)
                } catch (e: Exception) {
                    // Log but don't crash
                }
            }
        }
    }

    // ============ Postgres Callbacks ============

    /**
     * Add a callback for Postgres change events
     *
     * @param config Filter configuration
     * @param callback Function to call when change received
     * @return Callback ID for removal
     */
    fun addPostgresCallback(
        config: PostgresChangeConfig,
        callback: (PostgresAction) -> Unit
    ): String {
        val id = UUID.randomUUID().toString()
        postgresCallbacks[id] = PostgresCallbackEntry(config, callback)
        return id
    }

    /**
     * Trigger Postgres callbacks for a change event
     */
    fun triggerPostgresChange(action: PostgresAction) {
        postgresCallbacks.values.forEach { entry ->
            if (matchesConfig(action, entry.config)) {
                try {
                    entry.callback(action)
                } catch (e: Exception) {
                    // Log but don't crash
                }
            }
        }
    }

    private fun matchesConfig(action: PostgresAction, config: PostgresChangeConfig): Boolean {
        // Check schema
        if (action.schema != config.schema) return false

        // Check table (if specified)
        if (config.table != null && action.table != config.table) return false

        // Check event type
        val eventType = when (action) {
            is PostgresAction.Insert -> "INSERT"
            is PostgresAction.Update -> "UPDATE"
            is PostgresAction.Delete -> "DELETE"
        }
        if (config.event != "*" && config.event != eventType) return false

        // Apply client-side filter if specified
        // Filter format: "column=operator.value" (e.g., "user_id=eq.123")
        if (config.filter != null) {
            val record = when (action) {
                is PostgresAction.Insert -> action.record
                is PostgresAction.Update -> action.record
                is PostgresAction.Delete -> action.oldRecord
            }
            if (!matchesFilter(record, config.filter)) return false
        }

        return true
    }

    /**
     * Check if a record matches the filter expression.
     * Supports filter format: "column=operator.value"
     * Operators: eq, neq, gt, gte, lt, lte, in
     */
    private fun matchesFilter(record: kotlinx.serialization.json.JsonObject, filter: String): Boolean {
        // Parse filter: "column=operator.value"
        val eqIndex = filter.indexOf('=')
        if (eqIndex == -1) return true // Invalid filter, pass through

        val column = filter.substring(0, eqIndex)
        val rest = filter.substring(eqIndex + 1)

        // Parse operator and value: "operator.value"
        val dotIndex = rest.indexOf('.')
        if (dotIndex == -1) return true // Invalid filter, pass through

        val operator = rest.substring(0, dotIndex)
        val filterValue = rest.substring(dotIndex + 1)

        // Get the column value from record
        val recordValue = record[column]?.let { element ->
            when {
                element is kotlinx.serialization.json.JsonPrimitive -> element.content
                else -> element.toString().removeSurrounding("\"")
            }
        } ?: return false // Column not found, doesn't match

        // Apply operator
        return when (operator) {
            "eq" -> recordValue == filterValue
            "neq" -> recordValue != filterValue
            "gt" -> recordValue.toDoubleOrNull()?.let { it > filterValue.toDouble() } ?: false
            "gte" -> recordValue.toDoubleOrNull()?.let { it >= filterValue.toDouble() } ?: false
            "lt" -> recordValue.toDoubleOrNull()?.let { it < filterValue.toDouble() } ?: false
            "lte" -> recordValue.toDoubleOrNull()?.let { it <= filterValue.toDouble() } ?: false
            "in" -> {
                // Format: "in.(val1,val2,val3)"
                val values = filterValue.removeSurrounding("(", ")").split(",")
                recordValue in values
            }
            else -> true // Unknown operator, pass through
        }
    }

    // ============ Presence Callbacks ============

    /**
     * Add a callback for presence events
     *
     * @param callback Function to call when presence changes
     * @return Callback ID for removal
     */
    fun addPresenceCallback(callback: (PresenceAction) -> Unit): String {
        val id = UUID.randomUUID().toString()
        presenceCallbacks[id] = PresenceCallbackEntry(callback)
        return id
    }

    /**
     * Trigger presence callbacks
     */
    fun triggerPresence(action: PresenceAction) {
        presenceCallbacks.values.forEach { entry ->
            try {
                entry.callback(action)
            } catch (e: Exception) {
                // Log but don't crash
            }
        }
    }

    // ============ Removal ============

    /**
     * Remove a callback by ID
     */
    fun removeCallbackById(id: String) {
        broadcastCallbacks.remove(id)
        postgresCallbacks.remove(id)
        presenceCallbacks.remove(id)
    }

    /**
     * Clear all callbacks
     */
    fun clear() {
        broadcastCallbacks.clear()
        postgresCallbacks.clear()
        presenceCallbacks.clear()
    }

    // ============ Query Methods ============

    /**
     * Get all registered Postgres change configurations
     */
    fun getPostgresConfigs(): List<PostgresChangeConfig> {
        return postgresCallbacks.values.map { it.config }.distinctBy { it.toKey() }
    }

    /**
     * Check if any presence callbacks are registered
     */
    fun hasPresenceCallbacks(): Boolean = presenceCallbacks.isNotEmpty()

    /**
     * Check if any broadcast callbacks are registered
     */
    fun hasBroadcastCallbacks(): Boolean = broadcastCallbacks.isNotEmpty()
}

// ============ Internal Data Classes ============

private data class BroadcastCallbackEntry(
    val event: String,
    val callback: (JsonObject) -> Unit
)

private data class PostgresCallbackEntry(
    val config: PostgresChangeConfig,
    val callback: (PostgresAction) -> Unit
)

private data class PresenceCallbackEntry(
    val callback: (PresenceAction) -> Unit
)
