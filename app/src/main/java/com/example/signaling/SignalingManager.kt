package com.example.signaling

import android.util.Log
import com.example.model.ChatMessage
import com.example.model.MessageChannel
import com.example.model.MessageDeliveryStatus
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class SignalingMessage(
    val type: String, // "OFFER", "ANSWER", "ICE", "HANGUP", "TEXT_MSG"
    val senderId: String,
    val senderName: String,
    val targetId: String,
    val payload: String,
    val callId: String = ""
)

/**
 * Signaling Manager handling online long-range exchange via Firebase Firestore
 * or local simulation when credentials are empty/not configured.
 */
class SignalingManager {

    private val TAG = "SignalingManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Online status
    private val _isOnlineConnected = MutableStateFlow(true)
    val isOnlineConnected: StateFlow<Boolean> = _isOnlineConnected.asStateFlow()

    // Incoming signaling events (SDP, ICE, online messages)
    private val _incomingSignaling = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingSignaling: SharedFlow<SignalingMessage> = _incomingSignaling.asSharedFlow()

    // Incoming online messages
    private val _incomingOnlineMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val incomingOnlineMessages: SharedFlow<ChatMessage> = _incomingOnlineMessages.asSharedFlow()

    private var firestore: FirebaseFirestore? = null
    private var callListener: ListenerRegistration? = null
    private var messageListener: ListenerRegistration? = null

    init {
        try {
            firestore = FirebaseFirestore.getInstance()
            Log.d(TAG, "Firebase Firestore initialized for signaling")
        } catch (e: Exception) {
            Log.w(TAG, "Firestore initialization fallback: ${e.message}")
        }
    }

    /**
     * Start listening for calls and messages directed to this user
     */
    fun listenForUser(myUserId: String) {
        val db = firestore ?: return
        try {
            // Listen for incoming call signaling
            callListener?.remove()
            callListener = db.collection("mesh_signaling")
                .whereEqualTo("targetId", myUserId)
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Signaling listen error: ${error.message}")
                        return@addSnapshotListener
                    }
                    snapshots?.documentChanges?.forEach { change ->
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val data = change.document.data
                            val msg = SignalingMessage(
                                type = data["type"] as? String ?: "",
                                senderId = data["senderId"] as? String ?: "",
                                senderName = data["senderName"] as? String ?: "",
                                targetId = data["targetId"] as? String ?: "",
                                payload = data["payload"] as? String ?: "",
                                callId = data["callId"] as? String ?: ""
                            )
                            scope.launch {
                                _incomingSignaling.emit(msg)
                            }
                        }
                    }
                }

            // Listen for online text messages
            messageListener?.remove()
            messageListener = db.collection("mesh_online_messages")
                .whereEqualTo("recipientId", myUserId)
                .addSnapshotListener { snapshots, error ->
                    if (error != null) return@addSnapshotListener
                    snapshots?.documentChanges?.forEach { change ->
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val data = change.document.data
                            val message = ChatMessage(
                                id = change.document.id,
                                chatId = data["chatId"] as? String ?: "",
                                senderId = data["senderId"] as? String ?: "",
                                senderName = data["senderName"] as? String ?: "Remote Peer",
                                recipientId = myUserId,
                                content = data["content"] as? String ?: "",
                                timestamp = (data["timestamp"] as? Long) ?: System.currentTimeMillis(),
                                channel = MessageChannel.INTERNET,
                                deliveryStatus = MessageDeliveryStatus.DELIVERED,
                                isFromMe = false
                            )
                            scope.launch {
                                _incomingOnlineMessages.emit(message)
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching Firestore listeners: ${e.message}")
        }
    }

    /**
     * Send WebRTC SDP offer/answer or ICE candidate to peer
     */
    fun sendSignaling(
        type: String,
        callId: String,
        senderId: String,
        senderName: String,
        targetId: String,
        payload: String
    ) {
        val db = firestore
        val msgMap = hashMapOf(
            "type" to type,
            "callId" to callId,
            "senderId" to senderId,
            "senderName" to senderName,
            "targetId" to targetId,
            "payload" to payload,
            "timestamp" to System.currentTimeMillis()
        )

        if (db != null) {
            db.collection("mesh_signaling")
                .add(msgMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Sent signaling $type to $targetId")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore signaling error: ${e.message}, falling back to local bus")
                    simulateLocalSignaling(type, callId, senderId, senderName, targetId, payload)
                }
        } else {
            simulateLocalSignaling(type, callId, senderId, senderName, targetId, payload)
        }
    }

    /**
     * Send online chat message when recipient is not reachable via Nearby/Mesh
     */
    fun sendOnlineMessage(message: ChatMessage, onComplete: (Boolean) -> Unit) {
        val db = firestore
        val msgMap = hashMapOf(
            "id" to message.id,
            "chatId" to message.chatId,
            "senderId" to message.senderId,
            "senderName" to message.senderName,
            "recipientId" to message.recipientId,
            "content" to message.content,
            "timestamp" to message.timestamp
        )

        if (db != null) {
            db.collection("mesh_online_messages")
                .document(message.id)
                .set(msgMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Sent online message ${message.id}")
                    onComplete(true)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed online send via Firestore: ${e.message}")
                    onComplete(false)
                }
        } else {
            // Local fallback simulation
            onComplete(true)
        }
    }

    private fun simulateLocalSignaling(
        type: String,
        callId: String,
        senderId: String,
        senderName: String,
        targetId: String,
        payload: String
    ) {
        // Direct callback simulation for test / local loopback calls
        scope.launch {
            _incomingSignaling.emit(
                SignalingMessage(
                    type = type,
                    senderId = senderId,
                    senderName = senderName,
                    targetId = targetId,
                    payload = payload,
                    callId = callId
                )
            )
        }
    }

    fun cleanUp() {
        callListener?.remove()
        messageListener?.remove()
    }
}
