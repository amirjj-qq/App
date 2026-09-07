package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.CallStatus
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.RoseError
import com.example.viewmodel.MessengerViewModel
import org.webrtc.SurfaceViewRenderer

@Composable
fun VideoCallScreen(
    viewModel: MessengerViewModel,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsState()

    // Local & Remote WebRTC SurfaceViewRenderers
    val localRenderer = remember { SurfaceViewRenderer(context) }
    val remoteRenderer = remember { SurfaceViewRenderer(context) }

    DisposableEffect(Unit) {
        viewModel.webRtcManager.initRenderers(localRenderer, remoteRenderer)
        onDispose {
            try {
                localRenderer.release()
                remoteRenderer.release()
            } catch (ignored: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Fullscreen Remote Video Track
        AndroidView(
            factory = { remoteRenderer },
            modifier = Modifier
                .fillMaxSize()
                .testTag("remote_video_view")
        )

        // Overlay status / Peer Info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.65f),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = callState.peerName.ifBlank { "Video Call" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (callState.isNearbyChannel) Icons.Default.NearMe else Icons.Default.Public,
                            contentDescription = null,
                            tint = if (callState.isNearbyChannel) NeonEmerald else ElectricCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (callState.isNearbyChannel) {
                                "Direct P2P Nearby (Zero Internet Data)"
                            } else {
                                "WebRTC Online Link (STUN/TURN NAT Traversal)"
                            },
                            color = if (callState.isNearbyChannel) NeonEmerald else ElectricCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (callState.status == CallStatus.OUTGOING_RINGING) {
                        Text(
                            text = "Ringing peer...",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Picture-in-Picture Local Self Preview (Top Right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 70.dp, end = 16.dp)
                .size(width = 110.dp, height = 160.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(2.dp, ElectricCyan, RoundedCornerShape(16.dp))
                .background(Color.DarkGray)
                .testTag("local_pip_video_view")
        ) {
            AndroidView(
                factory = { localRenderer },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Bottom Controls Overlay
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute Audio
            IconButton(
                onClick = { viewModel.toggleAudioMute() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (callState.isAudioMuted) RoseError else Color.White.copy(alpha = 0.2f))
                    .testTag("toggle_audio_button")
            ) {
                Icon(
                    imageVector = if (callState.isAudioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Toggle Audio",
                    tint = Color.White
                )
            }

            // End Call (Red button)
            FloatingActionButton(
                onClick = {
                    viewModel.endCall()
                    onEndCall()
                },
                containerColor = RoseError,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.testTag("end_call_button")
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End Call",
                    modifier = Modifier.size(28.dp)
                )
            }

            // Mute Video
            IconButton(
                onClick = { viewModel.toggleVideoMute() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (callState.isVideoMuted) RoseError else Color.White.copy(alpha = 0.2f))
                    .testTag("toggle_video_button")
            ) {
                Icon(
                    imageVector = if (callState.isVideoMuted) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    contentDescription = "Toggle Video",
                    tint = Color.White
                )
            }

            // Switch Camera
            IconButton(
                onClick = { viewModel.switchCamera() },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
                    .testTag("switch_camera_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Cameraswitch,
                    contentDescription = "Switch Camera",
                    tint = Color.White
                )
            }
        }
    }
}
