package io.insforge.realtime

/**
 * Configuration for the Realtime module
 */
class RealtimeConfig {
    /**
     * Auto-reconnect on connection loss
     */
    var autoReconnect: Boolean = true

    /**
     * Reconnect delay in milliseconds
     */
    var reconnectDelay: Long = 5000

    /**
     * Maximum reconnect attempts (0 = infinite)
     */
    var maxReconnectAttempts: Int = 0

    /**
     * Ping interval to keep connection alive (milliseconds)
     */
    var pingInterval: Long = 30000

    /**
     * Enable debug logging for WebSocket messages.
     * When true, all outgoing and incoming Socket.IO messages will be logged.
     */
    var debug: Boolean = false
}
