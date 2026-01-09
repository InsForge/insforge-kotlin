package dev.insforge.realtime.models

import kotlinx.serialization.Serializable

// ============ Channel Models ============

@Serializable
data class RealtimeChannel(
    val id: String,
    val pattern: String,
    val description: String? = null,
    val webhookUrls: List<String>? = null,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CreateChannelRequest(
    val pattern: String,
    val description: String? = null,
    val webhookUrls: List<String>? = null,
    val enabled: Boolean = true
)

@Serializable
data class UpdateChannelRequest(
    val pattern: String? = null,
    val description: String? = null,
    val webhookUrls: List<String>? = null,
    val enabled: Boolean? = null
)

@Serializable
data class DeleteChannelResponse(
    val message: String
)

// ============ Message Models ============

@Serializable
data class RealtimeMessage(
    val id: String = "",
    val eventName: String = "",
    val channelId: String? = null,
    val channelName: String = "",
    val payload: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap()),
    val senderType: String = "user", // "system" or "user"
    val senderId: String? = null,
    val wsAudienceCount: Int = 0,
    val whAudienceCount: Int = 0,
    val whDeliveredCount: Int = 0,
    val createdAt: String = ""
)

@Serializable
data class MessageStats(
    val totalMessages: Int,
    val whDeliveryRate: Double,
    val topEvents: List<EventCount>
)

@Serializable
data class EventCount(
    val eventName: String,
    val count: Int
)

// ============ Socket.IO Models ============

/**
 * Response for subscribe operations (used in Socket.IO ack callbacks)
 *
 * Server format (discriminated union):
 * - Success: { ok: true, channel: string }
 * - Failure: { ok: false, channel: string, error: { code: string, message: string } }
 */
data class SubscribeResponse(
    val ok: Boolean,
    val channel: String,
    val error: RealtimeError? = null
)

/**
 * Payload for realtime:error server event (for unsolicited errors like publish failures)
 *
 * Server format:
 * - channel?: string (optional)
 * - code: string
 * - message: string
 */
data class RealtimeError(
    val code: String,
    val message: String,
    val channel: String? = null
)

/**
 * Meta object included in all socket messages
 *
 * Server format (socketMessageMetaSchema):
 * - channel?: string (present for room broadcasts)
 * - messageId: string (UUID)
 * - senderType: 'system' | 'user'
 * - senderId?: string (UUID)
 * - timestamp: string (datetime)
 */
data class SocketMessageMeta(
    val channel: String? = null,
    val messageId: String,
    val senderType: String,  // "system" or "user"
    val senderId: String? = null,
    val timestamp: String
)

/**
 * Incoming message from Socket.IO
 *
 * Server format (socketMessageSchema):
 * {
 *   meta: SocketMessageMeta,
 *   ...passthrough (payload fields at root level)
 * }
 *
 * The actual event payload is in the root object alongside meta.
 * For convenience, we also store the event name separately.
 */
data class SocketMessage(
    val meta: SocketMessageMeta,
    val event: String,
    val payload: kotlinx.serialization.json.JsonObject
) {
    // Convenience accessors
    val channel: String? get() = meta.channel
    val messageId: String get() = meta.messageId
    val senderType: String get() = meta.senderType
    val senderId: String? get() = meta.senderId
    val timestamp: String get() = meta.timestamp
}

/**
 * Payload for realtime:publish client event
 *
 * Server format (publishEventPayloadSchema):
 * - channel: string
 * - event: string
 * - payload: Record<string, unknown>
 */
data class PublishEventPayload(
    val channel: String,
    val event: String,
    val payload: Map<String, Any>
)

/**
 * Payload sent to webhook endpoints
 *
 * Server format (webhookMessageSchema):
 * - messageId: string (UUID)
 * - channel: string
 * - eventName: string
 * - payload: Record<string, unknown>
 */
@Serializable
data class WebhookMessage(
    val messageId: String,
    val channel: String,
    val eventName: String,
    val payload: kotlinx.serialization.json.JsonObject
)
