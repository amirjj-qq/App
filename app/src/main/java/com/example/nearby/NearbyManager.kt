package com.example.nearby

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.model.FileTransferState
import com.example.model.MeshPacket
import com.example.model.PacketType
import com.example.model.PeerConnectionState
import com.example.model.PeerDevice
import com.example.model.TransferStatus
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

data class IncomingConnectionRequest(
    val endpointId: String,
    val endpointName: String,
    val authenticationDigits: String
)

class NearbyManager(private val context: Context) {

    private val TAG = "NearbyManager"
    private val SERVICE_ID = "com.example.meshchat.service"
    private val STRATEGY = Strategy.P2P_CLUSTER // Multi-peer cluster topology for mesh

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Device identity
    val myEndpointId = "NODE_" + (1000..9999).random()
    var myDisplayName: String = "Peer-${(100..999).random()}"

    // State flows
    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Discovered and connected endpoints
    private val _discoveredPeers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow<Map<String, PeerDevice>>(emptyMap())
    val connectedPeers: StateFlow<Map<String, PeerDevice>> = _connectedPeers.asStateFlow()

    // Incoming connection requests awaiting user approval
    private val _incomingRequests = MutableStateFlow<List<IncomingConnectionRequest>>(emptyList())
    val incomingRequests: StateFlow<List<IncomingConnectionRequest>> = _incomingRequests.asStateFlow()

    // Received mesh packets
    private val _incomingMeshPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 64)
    val incomingMeshPackets: SharedFlow<MeshPacket> = _incomingMeshPackets.asSharedFlow()

    // File transfer state
    private val _activeFileTransfers = MutableStateFlow<Map<Long, FileTransferState>>(emptyMap())
    val activeFileTransfers: StateFlow<Map<Long, FileTransferState>> = _activeFileTransfers.asStateFlow()

    // Push to Talk state
    private val _isPttTalking = MutableStateFlow(false)
    val isPttTalking: StateFlow<Boolean> = _isPttTalking.asStateFlow()

    private val _isReceivingPtt = MutableStateFlow(false)
    val isReceivingPtt: StateFlow<Boolean> = _isReceivingPtt.asStateFlow()

    private val _pttSpeakerName = MutableStateFlow<String?>(null)
    val pttSpeakerName: StateFlow<String?> = _pttSpeakerName.asStateFlow()

    // Mesh deduplication cache (LRU 500 entries) to prevent endless packet loops
    private val seenMessageIds = Collections.synchronizedSet(
        object : LinkedHashMap<String, Boolean>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 500
            }
        }.keys
    )

    // Mesh metrics
    private val _meshRelayCount = MutableStateFlow(0)
    val meshRelayCount: StateFlow<Int> = _meshRelayCount.asStateFlow()

    // Track active file payloads to file names
    private val payloadFileNames = ConcurrentHashMap<Long, String>()
    private val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()

    // PTT Recording variables
    private var pttAudioRecord: AudioRecord? = null
    private var pttPipeWrite: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var pttRecordingJob: Job? = null

    // Audio settings for Push-to-Talk PCM 16-bit
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    // --- 1. Advertising ---
    fun startAdvertising(displayName: String = myDisplayName) {
        myDisplayName = displayName
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()

        connectionsClient.startAdvertising(
            displayName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started successfully as $displayName")
            _isAdvertising.value = true
        }.addOnFailureListener { e ->
            Log.e(TAG, "Advertising failed: ${e.message}")
            _isAdvertising.value = false
        }
    }

    fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        _isAdvertising.value = false
        Log.d(TAG, "Advertising stopped")
    }

    // --- 2. Discovery ---
    fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started successfully")
            _isDiscovering.value = true
        }.addOnFailureListener { e ->
            Log.e(TAG, "Discovery failed: ${e.message}")
            _isDiscovering.value = false
        }
    }

    fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        _isDiscovering.value = false
        Log.d(TAG, "Discovery stopped")
    }

    // --- 3. Connection Request & Lifecycle ---
    fun requestConnection(endpointId: String, endpointName: String) {
        // Mark peer as connecting
        updatePeerStatus(endpointId, PeerConnectionState.CONNECTING)

        connectionsClient.requestConnection(
            myDisplayName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            Log.d(TAG, "Connection requested to $endpointName ($endpointId)")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Connection request failed: ${e.message}")
            updatePeerStatus(endpointId, PeerConnectionState.DISCOVERED)
        }
    }

    fun acceptConnection(endpointId: String) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                Log.d(TAG, "Accepted connection from $endpointId")
                removeIncomingRequest(endpointId)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to accept connection: ${e.message}")
                removeIncomingRequest(endpointId)
            }
    }

    fun rejectConnection(endpointId: String) {
        connectionsClient.rejectConnection(endpointId)
            .addOnSuccessListener {
                Log.d(TAG, "Rejected connection from $endpointId")
                removeIncomingRequest(endpointId)
                updatePeerStatus(endpointId, PeerConnectionState.REJECTED)
            }
            .addOnFailureListener {
                removeIncomingRequest(endpointId)
            }
    }

    fun disconnectPeer(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
        val currentConnected = _connectedPeers.value.toMutableMap()
        currentConnected.remove(endpointId)
        _connectedPeers.value = currentConnected
    }

    fun disconnectAll() {
        connectionsClient.stopAllEndpoints()
        _connectedPeers.value = emptyMap()
        _incomingRequests.value = emptyList()
    }

    private fun removeIncomingRequest(endpointId: String) {
        _incomingRequests.value = _incomingRequests.value.filter { it.endpointId != endpointId }
    }

    private fun updatePeerStatus(endpointId: String, state: PeerConnectionState) {
        val current = _discoveredPeers.value.toMutableMap()
        current[endpointId]?.let {
            current[endpointId] = it.copy(connectionState = state)
            _discoveredPeers.value = current
        }
    }

    // --- Connection Lifecycle Callbacks ---
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated from ${connectionInfo.endpointName} ($endpointId)")
            val request = IncomingConnectionRequest(
                endpointId = endpointId,
                endpointName = connectionInfo.endpointName,
                authenticationDigits = connectionInfo.authenticationDigits
            )
            _incomingRequests.value = _incomingRequests.value + request
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected to endpoint: $endpointId")
                    val peer = _discoveredPeers.value[endpointId] ?: PeerDevice(
                        id = endpointId,
                        name = "Peer-$endpointId",
                        connectionState = PeerConnectionState.CONNECTED,
                        isNearbyConnected = true
                    )
                    val connectedPeer = peer.copy(
                        connectionState = PeerConnectionState.CONNECTED,
                        isNearbyConnected = true,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )

                    val updatedConnected = _connectedPeers.value.toMutableMap()
                    updatedConnected[endpointId] = connectedPeer
                    _connectedPeers.value = updatedConnected

                    val updatedDiscovered = _discoveredPeers.value.toMutableMap()
                    updatedDiscovered[endpointId] = connectedPeer
                    _discoveredPeers.value = updatedDiscovered
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.d(TAG, "Connection rejected by: $endpointId")
                    updatePeerStatus(endpointId, PeerConnectionState.REJECTED)
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    Log.e(TAG, "Connection error with: $endpointId")
                    updatePeerStatus(endpointId, PeerConnectionState.DISCONNECTED)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from: $endpointId")
            val updatedConnected = _connectedPeers.value.toMutableMap()
            updatedConnected.remove(endpointId)
            _connectedPeers.value = updatedConnected

            updatePeerStatus(endpointId, PeerConnectionState.DISCONNECTED)
        }
    }

    // --- Endpoint Discovery Callbacks ---
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Discovered endpoint: ${info.endpointName} ($endpointId)")
            val peer = PeerDevice(
                id = endpointId,
                name = info.endpointName,
                connectionState = PeerConnectionState.DISCOVERED,
                isNearbyConnected = false,
                lastSeenTimestamp = System.currentTimeMillis()
            )
            val updated = _discoveredPeers.value.toMutableMap()
            updated[endpointId] = peer
            _discoveredPeers.value = updated
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
            val updated = _discoveredPeers.value.toMutableMap()
            updated.remove(endpointId)
            _discoveredPeers.value = updated
        }
    }

    // --- 4. Mesh Relay & Message Routing ---

    /**
     * Send a mesh packet:
     * - If target is directly connected, send directly
     * - Otherwise broadcast to all connected mesh peers to hop through intermediate nodes
     */
    fun sendMeshPacket(packet: MeshPacket) {
        // Record packet ID in seen cache
        seenMessageIds.add(packet.id)

        val connectedIds = _connectedPeers.value.keys.toList()
        if (connectedIds.isEmpty()) {
            Log.w(TAG, "Cannot send packet: No connected mesh peers")
            return
        }

        val bytes = packet.toByteArray()
        val payload = Payload.fromBytes(bytes)

        if (connectedIds.contains(packet.targetId)) {
            // Target is a direct 1-hop neighbor
            connectionsClient.sendPayload(packet.targetId, payload)
            Log.d(TAG, "Sent packet ${packet.id} DIRECTLY to ${packet.targetId}")
        } else {
            // Forward/broadcast to all connected peers for multi-hop mesh routing
            connectionsClient.sendPayload(connectedIds, payload)
            Log.d(TAG, "Broadcast packet ${packet.id} to ${connectedIds.size} mesh peers (TTL=${packet.ttl})")
        }
    }

    /**
     * Forward packet across intermediate nodes (Mesh Relay)
     */
    private fun relayMeshPacket(packet: MeshPacket, incomingEndpointId: String) {
        if (packet.ttl <= 1) {
            Log.d(TAG, "Packet ${packet.id} dropped: TTL expired")
            return
        }

        // Decrement TTL and increment hop count
        val forwarded = packet.forward()
        seenMessageIds.add(forwarded.id)

        // Broadcast to all connected peers EXCEPT the one it arrived from
        val forwardTargets = _connectedPeers.value.keys.filter { it != incomingEndpointId }
        if (forwardTargets.isNotEmpty()) {
            val payload = Payload.fromBytes(forwarded.toByteArray())
            connectionsClient.sendPayload(forwardTargets, payload)
            _meshRelayCount.value = _meshRelayCount.value + 1
            Log.d(TAG, "Relayed packet ${packet.id} to ${forwardTargets.size} peers (Hop #${forwarded.hopCount}, TTL=${forwarded.ttl})")
        }
    }

    // --- 5. Push to Talk (Walkie-Talkie PCM Stream) ---

    @SuppressLint("MissingPermission")
    fun startPushToTalk(targetEndpointId: String = "*") {
        if (_isPttTalking.value) return
        val connected = _connectedPeers.value.keys.toList()
        if (connected.isEmpty()) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ).coerceAtLeast(4096)

            pttAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )

            // Create pipe for streaming
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            pttPipeWrite = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            val streamPayload = Payload.fromStream(ParcelFileDescriptor.AutoCloseInputStream(readSide))

            // First broadcast a PTT META packet so peers show "Speaking..." banner
            val pttMetaPacket = MeshPacket(
                sourceId = myEndpointId,
                sourceName = myDisplayName,
                targetId = targetEndpointId,
                packetType = PacketType.PTT_META,
                payloadData = "START"
            )
            sendMeshPacket(pttMetaPacket)

            // Send audio stream payload to target or all connected peers
            val targets = if (targetEndpointId != "*" && connected.contains(targetEndpointId)) {
                listOf(targetEndpointId)
            } else {
                connected
            }
            connectionsClient.sendPayload(targets, streamPayload)

            pttAudioRecord?.startRecording()
            _isPttTalking.value = true

            // Stream audio samples in background thread
            pttRecordingJob = scope.launch(Dispatchers.IO) {
                val audioBuffer = ByteArray(bufferSize)
                try {
                    while (_isPttTalking.value && pttAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readBytes = pttAudioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                        if (readBytes > 0) {
                            pttPipeWrite?.write(audioBuffer, 0, readBytes)
                            pttPipeWrite?.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in PTT stream write: ${e.message}")
                } finally {
                    try {
                        pttPipeWrite?.close()
                    } catch (ignored: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PTT: ${e.message}")
            _isPttTalking.value = false
        }
    }

    fun stopPushToTalk() {
        if (!_isPttTalking.value) return
        _isPttTalking.value = false

        try {
            pttAudioRecord?.stop()
            pttAudioRecord?.release()
            pttAudioRecord = null
            pttRecordingJob?.cancel()
            pttPipeWrite?.close()
            pttPipeWrite = null

            // Send PTT STOP meta packet
            val stopPacket = MeshPacket(
                sourceId = myEndpointId,
                sourceName = myDisplayName,
                targetId = "*",
                packetType = PacketType.PTT_META,
                payloadData = "STOP"
            )
            sendMeshPacket(stopPacket)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping PTT: ${e.message}")
        }
    }

    // Play incoming audio stream in background
    private fun playIncomingAudioStream(inputStream: InputStream) {
        scope.launch(Dispatchers.IO) {
            var audioTrack: AudioTrack? = null
            _isReceivingPtt.value = true
            try {
                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT
                ).coerceAtLeast(4096)

                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )

                audioTrack.play()
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    audioTrack.write(buffer, 0, bytesRead)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio stream: ${e.message}")
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    inputStream.close()
                } catch (ignored: Exception) {}
                _isReceivingPtt.value = false
                _pttSpeakerName.value = null
            }
        }
    }

    // --- 6. File & Video Transfer ---

    fun sendFile(targetEndpointId: String, uri: Uri, fileName: String, fileSize: Long) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            val filePayload = Payload.fromFile(pfd)
            val payloadId = filePayload.id

            // Send metadata packet first so receiver knows the filename
            val metaPacket = MeshPacket(
                sourceId = myEndpointId,
                sourceName = myDisplayName,
                targetId = targetEndpointId,
                packetType = PacketType.FILE_META,
                payloadData = "$payloadId:$fileName:$fileSize"
            )
            sendMeshPacket(metaPacket)

            // Register transfer state
            val transferState = FileTransferState(
                transferId = payloadId,
                peerId = targetEndpointId,
                peerName = _connectedPeers.value[targetEndpointId]?.name ?: "Peer",
                fileName = fileName,
                totalBytes = fileSize,
                isIncoming = false,
                status = TransferStatus.IN_PROGRESS,
                filePathOrUri = uri.toString()
            )
            val updatedTransfers = _activeFileTransfers.value.toMutableMap()
            updatedTransfers[payloadId] = transferState
            _activeFileTransfers.value = updatedTransfers

            // Send the file payload
            connectionsClient.sendPayload(targetEndpointId, filePayload)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send file payload: ${e.message}")
                    val current = _activeFileTransfers.value.toMutableMap()
                    current[payloadId]?.let {
                        current[payloadId] = it.copy(status = TransferStatus.FAILED)
                        _activeFileTransfers.value = current
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "File open error: ${e.message}")
        }
    }

    // --- 7. Payload Callbacks ---
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val packet = MeshPacket.fromByteArray(bytes) ?: return

                    // Check deduplication cache
                    if (seenMessageIds.contains(packet.id)) {
                        return // Already seen/processed
                    }
                    seenMessageIds.add(packet.id)

                    val isForMe = packet.targetId == myEndpointId ||
                                  packet.targetId == "*" ||
                                  packet.targetId == myDisplayName

                    if (packet.packetType == PacketType.PTT_META) {
                        if (packet.payloadData == "START") {
                            _isReceivingPtt.value = true
                            _pttSpeakerName.value = packet.sourceName
                        } else {
                            _isReceivingPtt.value = false
                            _pttSpeakerName.value = null
                        }
                    } else if (packet.packetType == PacketType.FILE_META) {
                        // "payloadId:fileName:fileSize"
                        val parts = packet.payloadData.split(":")
                        if (parts.size >= 3) {
                            val pId = parts[0].toLongOrNull() ?: 0L
                            val fName = parts[1]
                            val fSize = parts[2].toLongOrNull() ?: 0L
                            payloadFileNames[pId] = fName

                            val transferState = FileTransferState(
                                transferId = pId,
                                peerId = packet.sourceId,
                                peerName = packet.sourceName,
                                fileName = fName,
                                totalBytes = fSize,
                                isIncoming = true,
                                status = TransferStatus.IN_PROGRESS
                            )
                            val updated = _activeFileTransfers.value.toMutableMap()
                            updated[pId] = transferState
                            _activeFileTransfers.value = updated
                        }
                    }

                    if (isForMe) {
                        // Dispatch to ViewModel / UI
                        scope.launch {
                            _incomingMeshPackets.emit(packet)
                        }
                    }

                    // If not exclusively for me (or is a broadcast), forward/relay it across mesh!
                    if (packet.targetId != myEndpointId) {
                        relayMeshPacket(packet, endpointId)
                    }
                }

                Payload.Type.STREAM -> {
                    val inputStream = payload.asStream()?.asInputStream() ?: return
                    playIncomingAudioStream(inputStream)
                }

                Payload.Type.FILE -> {
                    // Incoming file payload
                    incomingFilePayloads[payload.id] = payload
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val payloadId = update.payloadId
            val currentTransfers = _activeFileTransfers.value.toMutableMap()
            val transfer = currentTransfers[payloadId]

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    transfer?.let {
                        currentTransfers[payloadId] = it.copy(
                            bytesTransferred = update.bytesTransferred,
                            totalBytes = if (update.totalBytes > 0) update.totalBytes else it.totalBytes,
                            status = TransferStatus.IN_PROGRESS
                        )
                        _activeFileTransfers.value = currentTransfers
                    }
                }

                PayloadTransferUpdate.Status.SUCCESS -> {
                    transfer?.let {
                        val finalFileName = payloadFileNames[payloadId] ?: it.fileName
                        // Move received file to app files dir
                        val incomingPayload = incomingFilePayloads.remove(payloadId)
                        val savedFile = saveReceivedFile(incomingPayload, finalFileName)

                        currentTransfers[payloadId] = it.copy(
                            bytesTransferred = it.totalBytes,
                            status = TransferStatus.COMPLETED,
                            filePathOrUri = savedFile?.absolutePath
                        )
                        _activeFileTransfers.value = currentTransfers
                    }
                }

                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    transfer?.let {
                        currentTransfers[payloadId] = it.copy(status = TransferStatus.FAILED)
                        _activeFileTransfers.value = currentTransfers
                    }
                    incomingFilePayloads.remove(payloadId)
                }
            }
        }
    }

    private fun saveReceivedFile(payload: Payload?, fileName: String): File? {
        return try {
            val javaFile = payload?.asFile()?.asJavaFile()
            val targetDir = File(context.filesDir, "received_files").apply { mkdirs() }
            val destFile = File(targetDir, fileName)

            if (javaFile != null && javaFile.exists()) {
                javaFile.renameTo(destFile)
                destFile
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error moving received file: ${e.message}")
            null
        }
    }
}
