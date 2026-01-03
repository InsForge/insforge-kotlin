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
}
