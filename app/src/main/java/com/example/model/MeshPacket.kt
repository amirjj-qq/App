package com.example.model

import org.json.JSONObject
import java.util.UUID

enum class PacketType {
    TEXT,
    PTT_META,
    FILE_META,
    MESH_ACK,
    WEBRTC_SIGNAL
}

/**
 * Wire packet for Nearby Connections P2P Mesh Relay
 * Each packet includes UUID, source, target, TTL counter, hop count, and payload data.
 */
data class MeshPacket(
    val id: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val sourceName: String,
    val targetId: String,       // Target peer ID or "*" for mesh broadcast
    val packetType: PacketType = PacketType.TEXT,
    val payloadData: String,
    val ttl: Int = 5,           // Maximum hops allowed to prevent network loops
    val hopCount: Int = 0,      // Number of hops traversed
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toByteArray(): ByteArray {
        val json = JSONObject().apply {
            put("id", id)
            put("sourceId", sourceId)
            put("sourceName", sourceName)
            put("targetId", targetId)
            put("packetType", packetType.name)
            put("payloadData", payloadData)
            put("ttl", ttl)
            put("hopCount", hopCount)
            put("timestamp", timestamp)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Create a forwarded replica for mesh relay:
     * - Decrements TTL
     * - Increments hop count
     */
    fun forward(): MeshPacket {
        return copy(
            ttl = ttl - 1,
            hopCount = hopCount + 1
        )
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): MeshPacket? {
            return try {
                val str = String(bytes, Charsets.UTF_8)
                val json = JSONObject(str)
                MeshPacket(
                    id = json.getString("id"),
                    sourceId = json.getString("sourceId"),
                    sourceName = json.getString("sourceName"),
                    targetId = json.getString("targetId"),
                    packetType = PacketType.valueOf(json.optString("packetType", PacketType.TEXT.name)),
                    payloadData = json.getString("payloadData"),
                    ttl = json.optInt("ttl", 5),
                    hopCount = json.optInt("hopCount", 0),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis())
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
