package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ChatDatabase
import com.example.data.ChatRepository
import com.example.model.CallState
import com.example.model.CallStatus
import com.example.model.ChatMessage
import com.example.model.FileTransferState
import com.example.model.MessageChannel
import com.example.model.MessageDeliveryStatus
import com.example.model.MessageType
import com.example.model.MeshPacket
import com.example.model.PacketType
import com.example.model.PeerChannel
import com.example.model.PeerConnectionState
import com.example.model.PeerDevice
import com.example.nearby.IncomingConnectionRequest
import com.example.nearby.NearbyManager
import com.example.signaling.SignalingManager
import com.example.signaling.SignalingMessage
import com.example.webrtc.WebRtcManager
import com.example.ui.screens.checkNearbyPermissionsGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class MessengerViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MessengerViewModel"

    private val database = ChatDatabase.getInstance(application)
    val repository = ChatRepository(database.chatDao())

    val nearbyManager = NearbyManager(application)
    val signalingManager = SignalingManager()

    lateinit var webRtcManager: WebRtcManager
        private set

    // Current user profile
    val myUserId: String = nearbyManager.myEndpointId
    val myDisplayName = MutableStateFlow("Peer-${myUserId.takeLast(4)}")

    // UI state
    val isAdvertising: StateFlow<Boolean> = nearbyManager.isAdvertising
    val isDiscovering: StateFlow<Boolean> = nearbyManager.isDiscovering
    val discoveredPeers: StateFlow<Map<String, PeerDevice>> = nearbyManager.discoveredPeers
    val connectedPeers: StateFlow<Map<String, PeerDevice>> = nearbyManager.connectedPeers
    val incomingRequests: StateFlow<List<IncomingConnectionRequest>> = nearbyManager.incomingRequests

    // Push-to-Talk state
    val isPttTalking: StateFlow<Boolean> = nearbyManager.isPttTalking
    val isReceivingPtt: StateFlow<Boolean> = nearbyManager.isReceivingPtt
    val pttSpeakerName: StateFlow<String?> = nearbyManager.pttSpeakerName

    // Active file transfers
    val activeFileTransfers: StateFlow<Map<Long, FileTransferState>> = nearbyManager.activeFileTransfers

    // Mesh stats
    val meshRelayCount: StateFlow<Int> = nearbyManager.meshRelayCount

    // Active selected chat ID
    private val _selectedChatId = MutableStateFlow<String?>(null)
    val selectedChatId: StateFlow<String?> = _selectedChatId.asStateFlow()

    // Active chat messages
    private val _currentChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentChatMessages: StateFlow<List<ChatMessage>> = _currentChatMessages.asStateFlow()

    // All persistent messages from Room
    val allMessages: StateFlow<List<ChatMessage>> = repository.getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All contacts/peers
    val persistentPeers: StateFlow<List<PeerDevice>> = repository.getAllPeers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Call state
    private val _callState = MutableStateFlow(CallState())
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    init {
        // Initialize WebRTC
        webRtcManager = WebRtcManager(application) { type, callId, payload ->
            val peer = _callState.value.peerId
            // Check if call should be signaled via Nearby Mesh or Internet
            if (nearbyManager.connectedPeers.value.containsKey(peer)) {
                // Route signaling over Nearby Mesh packet
                val packet = MeshPacket(
                    sourceId = myUserId,
                    sourceName = myDisplayName.value,
                    targetId = peer,
                    packetType = PacketType.WEBRTC_SIGNAL,
                    payloadData = "$type:$callId:$payload"
                )
                nearbyManager.sendMeshPacket(packet)
            } else {
                // Route signaling over Firebase/Internet
                signalingManager.sendSignaling(
                    type = type,
                    callId = callId,
                    senderId = myUserId,
                    senderName = myDisplayName.value,
                    targetId = peer,
                    payload = payload
                )
            }
        }

        // Listen for user on signaling
        signalingManager.listenForUser(myUserId)

        // Observe incoming mesh packets
        viewModelScope.launch {
            nearbyManager.incomingMeshPackets.collect { packet ->
                handleIncomingMeshPacket(packet)
            }
        }

        // Observe incoming internet signaling
        viewModelScope.launch {
            signalingManager.incomingSignaling.collect { sig ->
                handleIncomingSignaling(sig)
            }
        }

        // Observe incoming online messages
        viewModelScope.launch {
            signalingManager.incomingOnlineMessages.collect { msg ->
                repository.saveMessage(msg)
            }
        }

        // Update selected chat messages
        viewModelScope.launch {
            _selectedChatId.collect { chatId ->
                if (chatId != null) {
                    repository.getMessagesForChat(chatId).collect { msgs ->
                        _currentChatMessages.value = msgs
                    }
                } else {
                    _currentChatMessages.value = emptyList()
                }
            }
        }

        // Automatically start advertising and discovery on launch if permissions are granted
        viewModelScope.launch {
            delay(500)
            if (checkNearbyPermissionsGranted(getApplication())) {
                startMeshNetwork()
            } else {
                Log.d(TAG, "Awaiting permissions before starting Nearby Mesh network")
            }
        }
    }

    fun startMeshNetwork() {
        if (!checkNearbyPermissionsGranted(getApplication())) {
            Log.w(TAG, "Cannot start mesh network: Nearby permissions not yet granted")
            return
        }
        nearbyManager.startAdvertising(myDisplayName.value)
        nearbyManager.startDiscovery()
    }

    fun stopMeshNetwork() {
        nearbyManager.stopAdvertising()
        nearbyManager.stopDiscovery()
    }

    fun updateMyName(newName: String) {
        if (newName.isNotBlank()) {
            myDisplayName.value = newName
            nearbyManager.stopAdvertising()
            nearbyManager.startAdvertising(newName)
        }
    }

    fun selectChat(chatId: String) {
        _selectedChatId.value = chatId
    }

    /**
     * Send a text message:
     * - Automatic switching between Nearby/Mesh and Online (Internet)
     */
    fun sendMessage(recipientId: String, recipientName: String, text: String) {
        if (text.isBlank()) return

        val isConnectedNearby = nearbyManager.connectedPeers.value.containsKey(recipientId)
        val isReachableNearby = isConnectedNearby || nearbyManager.discoveredPeers.value.containsKey(recipientId)

        val channel = when {
            isConnectedNearby -> MessageChannel.NEARBY_DIRECT
            isReachableNearby || nearbyManager.connectedPeers.value.isNotEmpty() -> MessageChannel.MESH_RELAY
            else -> MessageChannel.INTERNET
        }

        val message = ChatMessage(
            chatId = recipientId,
            senderId = myUserId,
            senderName = myDisplayName.value,
            recipientId = recipientId,
            recipientName = recipientName,
            content = text,
            channel = channel,
            deliveryStatus = MessageDeliveryStatus.SENDING,
            type = MessageType.TEXT,
            isFromMe = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            repository.saveMessage(message)

            if (channel == MessageChannel.NEARBY_DIRECT || channel == MessageChannel.MESH_RELAY) {
                // Route through Nearby Connections Mesh
                val meshPacket = MeshPacket(
                    id = message.id,
                    sourceId = myUserId,
                    sourceName = myDisplayName.value,
                    targetId = recipientId,
                    packetType = PacketType.TEXT,
                    payloadData = text,
                    ttl = 5,
                    hopCount = 0
                )
                nearbyManager.sendMeshPacket(meshPacket)
                val newStatus = if (channel == MessageChannel.NEARBY_DIRECT) {
                    MessageDeliveryStatus.DELIVERED
                } else {
                    MessageDeliveryStatus.RELAYED
                }
                repository.updateMessageStatus(message.id, newStatus)
            } else {
                // Route through Internet / Signaling
                signalingManager.sendOnlineMessage(message) { success ->
                    viewModelScope.launch {
                        val status = if (success) MessageDeliveryStatus.DELIVERED else MessageDeliveryStatus.FAILED
                        repository.updateMessageStatus(message.id, status)
                    }
                }
            }

            // Save peer in history
            repository.insertOrUpdatePeer(
                PeerDevice(
                    id = recipientId,
                    name = recipientName,
                    channel = if (channel == MessageChannel.INTERNET) PeerChannel.INTERNET_ONLINE else PeerChannel.NEARBY_DIRECT,
                    isNearbyConnected = isConnectedNearby,
                    isOnlineAvailable = channel == MessageChannel.INTERNET
                )
            )
        }
    }

    /**
     * Send file attachment
     */
    fun sendFile(recipientId: String, recipientName: String, uri: Uri, fileName: String, fileSize: Long) {
        val isConnectedNearby = nearbyManager.connectedPeers.value.containsKey(recipientId)

        val channel = if (isConnectedNearby) MessageChannel.NEARBY_DIRECT else MessageChannel.INTERNET

        val message = ChatMessage(
            chatId = recipientId,
            senderId = myUserId,
            senderName = myDisplayName.value,
            recipientId = recipientId,
            recipientName = recipientName,
            content = "File: $fileName (${FileTransferState(0, recipientId, recipientName, fileName, fileSize).formattedSize})",
            channel = channel,
            deliveryStatus = MessageDeliveryStatus.SENDING,
            type = MessageType.FILE_ATTACHMENT,
            fileUri = uri.toString(),
            fileName = fileName,
            fileSize = fileSize,
            isFromMe = true
        )

        viewModelScope.launch(Dispatchers.IO) {
            repository.saveMessage(message)
            if (isConnectedNearby) {
                nearbyManager.sendFile(recipientId, uri, fileName, fileSize)
            } else {
                // Online file sharing: in real production uploaded to Firebase Storage;
                // Here we deliver online download reference to peer!
                signalingManager.sendOnlineMessage(message) {
                    viewModelScope.launch {
                        repository.updateMessageStatus(message.id, MessageDeliveryStatus.DELIVERED)
                    }
                }
            }
        }
    }

    // Push to Talk actions
    fun startPtt(targetEndpointId: String = "*") {
        nearbyManager.startPushToTalk(targetEndpointId)
    }

    fun stopPtt() {
        nearbyManager.stopPushToTalk()
    }

    // --- Video Calling ---
    fun startVideoCall(peerId: String, peerName: String) {
        val isNearby = nearbyManager.connectedPeers.value.containsKey(peerId)
        val callId = UUID.randomUUID().toString()

        _callState.value = CallState(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            status = CallStatus.OUTGOING_RINGING,
            isNearbyChannel = isNearby
        )

        webRtcManager.startLocalMedia()
        webRtcManager.initiateCall(
            callId = callId,
            onConnected = {
                _callState.value = _callState.value.copy(status = CallStatus.CONNECTED)
            },
            onDisconnected = {
                endCall()
            }
        )
    }

    fun acceptCall() {
        val state = _callState.value
        _callState.value = state.copy(status = CallStatus.CONNECTING)

        webRtcManager.startLocalMedia()
        val offerSdp = incomingOffers[state.callId] ?: ""
        webRtcManager.answerCall(
            callId = state.callId,
            remoteOfferSdp = offerSdp,
            onConnected = {
                _callState.value = _callState.value.copy(status = CallStatus.CONNECTED)
            },
            onDisconnected = {
                endCall()
            }
        )
    }

    fun declineCall() {
        val state = _callState.value
        if (state.status != CallStatus.IDLE) {
            signalingManager.sendSignaling(
                type = "HANGUP",
                callId = state.callId,
                senderId = myUserId,
                senderName = myDisplayName.value,
                targetId = state.peerId,
                payload = "DECLINED"
            )
        }
        endCall()
    }

    fun endCall() {
        webRtcManager.endCall()
        _callState.value = CallState(status = CallStatus.IDLE)
    }

    fun toggleAudioMute() {
        val current = _callState.value
        val newMute = !current.isAudioMuted
        webRtcManager.toggleAudio(newMute)
        _callState.value = current.copy(isAudioMuted = newMute)
    }

    fun toggleVideoMute() {
        val current = _callState.value
        val newMute = !current.isVideoMuted
        webRtcManager.toggleVideo(newMute)
        _callState.value = current.copy(isVideoMuted = newMute)
    }

    fun switchCamera() {
        webRtcManager.switchCamera()
        _callState.value = _callState.value.copy(isFrontCamera = !_callState.value.isFrontCamera)
    }

    private val incomingOffers = mutableMapOf<String, String>()

    private fun handleIncomingMeshPacket(packet: MeshPacket) {
        when (packet.packetType) {
            PacketType.TEXT -> {
                val channel = if (packet.hopCount > 0) MessageChannel.MESH_RELAY else MessageChannel.NEARBY_DIRECT
                val chatMessage = ChatMessage(
                    id = packet.id,
                    chatId = packet.sourceId,
                    senderId = packet.sourceId,
                    senderName = packet.sourceName,
                    recipientId = myUserId,
                    content = packet.payloadData,
                    timestamp = packet.timestamp,
                    channel = channel,
                    deliveryStatus = MessageDeliveryStatus.DELIVERED,
                    type = MessageType.TEXT,
                    hopCount = packet.hopCount,
                    isFromMe = false
                )
                viewModelScope.launch(Dispatchers.IO) {
                    repository.saveMessage(chatMessage)
                    repository.insertOrUpdatePeer(
                        PeerDevice(
                            id = packet.sourceId,
                            name = packet.sourceName,
                            channel = if (packet.hopCount > 0) PeerChannel.MESH_HOP else PeerChannel.NEARBY_DIRECT,
                            isNearbyConnected = nearbyManager.connectedPeers.value.containsKey(packet.sourceId),
                            hopsAway = packet.hopCount.coerceAtLeast(1)
                        )
                    )
                }
            }

            PacketType.WEBRTC_SIGNAL -> {
                // "type:callId:payload"
                val parts = packet.payloadData.split(":", limit = 3)
                if (parts.size == 3) {
                    val type = parts[0]
                    val callId = parts[1]
                    val payload = parts[2]
                    handleIncomingSignaling(
                        SignalingMessage(
                            type = type,
                            senderId = packet.sourceId,
                            senderName = packet.sourceName,
                            targetId = myUserId,
                            payload = payload,
                            callId = callId
                        )
                    )
                }
            }

            else -> {}
        }
    }

    private fun handleIncomingSignaling(sig: SignalingMessage) {
        when (sig.type) {
            "OFFER" -> {
                incomingOffers[sig.callId] = sig.payload
                _callState.value = CallState(
                    callId = sig.callId,
                    peerId = sig.senderId,
                    peerName = sig.senderName,
                    status = CallStatus.INCOMING_RINGING,
                    isNearbyChannel = nearbyManager.connectedPeers.value.containsKey(sig.senderId)
                )
            }

            "ANSWER" -> {
                webRtcManager.handleRemoteAnswer(sig.payload)
                _callState.value = _callState.value.copy(status = CallStatus.CONNECTED)
            }

            "ICE" -> {
                webRtcManager.handleRemoteCandidate(sig.payload)
            }

            "HANGUP" -> {
                endCall()
            }
        }
    }
}
