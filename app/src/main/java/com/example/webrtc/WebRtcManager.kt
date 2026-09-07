package com.example.webrtc

import android.content.Context
import android.util.Log
import com.example.model.CallState
import com.example.model.CallStatus
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

class WebRtcManager(
    private val context: Context,
    private val onSendSignaling: (type: String, callId: String, payload: String) -> Unit
) {
    private val TAG = "WebRtcManager"

    val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var remoteVideoTrack: VideoTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    var currentCallId: String = ""
        private set

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            Log.d(TAG, "PeerConnectionFactory initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC factory: ${e.message}")
        }
    }

    fun initRenderers(local: SurfaceViewRenderer, remote: SurfaceViewRenderer) {
        this.localRenderer = local
        this.remoteRenderer = remote

        try {
            local.init(eglBase.eglBaseContext, null)
            local.setEnableHardwareScaler(true)
            local.setMirror(true)
            local.setZOrderMediaOverlay(true) // For PiP preview on top

            remote.init(eglBase.eglBaseContext, null)
            remote.setEnableHardwareScaler(true)
            remote.setMirror(false)
        } catch (e: Exception) {
            Log.e(TAG, "Renderer initialization: ${e.message}")
        }
    }

    fun startLocalMedia() {
        val factory = peerConnectionFactory ?: return
        try {
            // Audio
            val audioConstraints = MediaConstraints()
            localAudioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)

            // Video Capturer
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames
            var chosenDevice: String? = null

            for (device in deviceNames) {
                if (enumerator.isFrontFacing(device)) {
                    chosenDevice = device
                    break
                }
            }
            if (chosenDevice == null && deviceNames.isNotEmpty()) {
                chosenDevice = deviceNames[0]
            }

            if (chosenDevice != null) {
                videoCapturer = enumerator.createCapturer(chosenDevice, null) as? CameraVideoCapturer
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
                localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)

                videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
                videoCapturer?.startCapture(640, 480, 30)

                localVideoTrack = factory.createVideoTrack("ARDAMSv0", localVideoSource)
                localRenderer?.let { localVideoTrack?.addSink(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting local media: ${e.message}")
        }
    }

    private fun createPeerConnection(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        val factory = peerConnectionFactory ?: return

        // Google public STUN servers for NAT traversal
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE Connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> onConnected()
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> onDisconnected()
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = JSONObject().apply {
                        put("sdpMid", it.sdpMid)
                        put("sdpMLineIndex", it.sdpMLineIndex)
                        put("sdp", it.sdp)
                    }
                    onSendSignaling("ICE", currentCallId, json.toString())
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { track ->
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    remoteRenderer?.let { track.addSink(it) }
                }
            }
        })

        // Add local tracks
        localAudioTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }
        localVideoTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }
    }

    fun initiateCall(callId: String = UUID.randomUUID().toString(), onConnected: () -> Unit, onDisconnected: () -> Unit) {
        currentCallId = callId
        createPeerConnection(onConnected, onDisconnected)

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            onSendSignaling("OFFER", currentCallId, it.description)
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Create offer failed: $error")
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    fun answerCall(callId: String, remoteOfferSdp: String, onConnected: () -> Unit, onDisconnected: () -> Unit) {
        currentCallId = callId
        createPeerConnection(onConnected, onDisconnected)

        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        answerDesc?.let {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    onSendSignaling("ANSWER", currentCallId, it.description)
                                }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            }, it)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, remoteDesc)
    }

    fun handleRemoteAnswer(remoteAnswerSdp: String) {
        val answerDesc = SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, answerDesc)
    }

    fun handleRemoteCandidate(candidateJson: String) {
        try {
            val json = JSONObject(candidateJson)
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("sdp")
            )
            peerConnection?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding ICE candidate: ${e.message}")
        }
    }

    fun toggleAudio(mute: Boolean) {
        localAudioTrack?.setEnabled(!mute)
    }

    fun toggleVideo(mute: Boolean) {
        localVideoTrack?.setEnabled(!mute)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun endCall() {
        try {
            peerConnection?.close()
            peerConnection = null

            try {
                videoCapturer?.stopCapture()
            } catch (ignored: Exception) {}
            videoCapturer?.dispose()
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoTrack?.dispose()
            localVideoTrack = null
            localAudioTrack?.dispose()
            localAudioTrack = null

            localVideoSource?.dispose()
            localVideoSource = null
            localAudioSource?.dispose()
            localAudioSource = null
        } catch (e: Exception) {
            Log.e(TAG, "Error ending call: ${e.message}")
        }
    }
}
