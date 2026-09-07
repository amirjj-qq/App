package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class MessageChannel {
    NEARBY_DIRECT, // Direct connection via Google Nearby
    MESH_RELAY,    // Forwarded across intermediate peers (multi-hop)
    INTERNET       // Via Online signaling / cloud
}

enum class MessageDeliveryStatus {
    SENDING,
    SENT,
    RELAYED,
    DELIVERED,
    FAILED
}

enum class MessageType {
    TEXT,
    PTT_AUDIO,
    FILE_ATTACHMENT,
    SYSTEM_EVENT
}

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val chatId: String,           // Unique peer ID or group conversation ID
    val senderId: String,         // Endpoint or user ID of original sender
    val senderName: String,       // Display name of sender
    val recipientId: String,      // Target peer ID or "*" for mesh broadcast
    val recipientName: String = "",
    val content: String,          // Text content or descriptive payload summary
    val timestamp: Long = System.currentTimeMillis(),
    val channel: MessageChannel = MessageChannel.NEARBY_DIRECT,
    val deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENDING,
    val type: MessageType = MessageType.TEXT,
    val hopCount: Int = 0,        // Number of hops traversed in mesh
    val ttl: Int = 5,             // Time To Live
    val fileUri: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0,
    val isFromMe: Boolean = true
)
