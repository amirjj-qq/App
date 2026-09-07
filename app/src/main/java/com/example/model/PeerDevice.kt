package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class PeerConnectionState {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    REJECTED,
    DISCONNECTED
}

enum class PeerChannel {
    NEARBY_DIRECT,
    MESH_HOP,
    INTERNET_ONLINE
}

@Entity(tableName = "peers")
data class PeerDevice(
    @PrimaryKey
    val id: String,                 // Endpoint ID for Nearby, or User UUID for Online
    val name: String,               // Display name
    val connectionState: PeerConnectionState = PeerConnectionState.DISCOVERED,
    val channel: PeerChannel = PeerChannel.NEARBY_DIRECT,
    val isNearbyConnected: Boolean = false,
    val isOnlineAvailable: Boolean = false,
    val hopsAway: Int = 1,          // 1 if directly connected, > 1 if reachable via mesh relay
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false
)
