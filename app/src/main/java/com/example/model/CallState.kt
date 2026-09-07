package com.example.model

enum class CallStatus {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    CONNECTED,
    ENDED
}

data class CallState(
    val callId: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val status: CallStatus = CallStatus.IDLE,
    val isAudioMuted: Boolean = false,
    val isVideoMuted: Boolean = false,
    val isFrontCamera: Boolean = true,
    val isNearbyChannel: Boolean = false, // True if routed directly over Nearby Wi-Fi/P2P
    val durationSeconds: Long = 0L,
    val errorMessage: String? = null
)
