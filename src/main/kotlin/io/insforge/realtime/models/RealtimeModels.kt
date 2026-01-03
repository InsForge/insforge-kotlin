package io.insforge.realtime.models

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
    val id: String,
    val eventName: String,
    val channelId: String? = null,
    val channelName: String,
    val payload: Map<String, String>,
    val senderType: String, // "system" or "user"
    val senderId: String? = null,
    val wsAudienceCount: Int,
    val whAudienceCount: Int,
    val whDeliveredCount: Int,
    val createdAt: String
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
