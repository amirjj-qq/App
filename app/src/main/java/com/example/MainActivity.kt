package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.model.CallStatus
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.ChatsScreen
import com.example.ui.screens.DiscoveryScreen
import com.example.ui.screens.FileTransfersScreen
import com.example.ui.screens.VideoCallScreen
import com.example.ui.theme.CyberMeshTheme
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.RoseError
import com.example.viewmodel.MessengerViewModel

const val ROUTE_DISCOVERY = "discovery"
const val ROUTE_CHATS = "chats"
const val ROUTE_TRANSFERS = "transfers"
const val ROUTE_CHAT_DETAIL = "chat_detail/{peerId}/{peerName}"
const val ROUTE_VIDEO_CALL = "video_call"

class MainActivity : ComponentActivity() {

    private val viewModel: MessengerViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CyberMeshTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val callState by viewModel.callState.collectAsState()

                // If a video call becomes connected or outgoing ringing, navigate to VideoCallScreen
                val shouldShowCallScreen = callState.status == CallStatus.CONNECTED ||
                        callState.status == CallStatus.OUTGOING_RINGING ||
                        callState.status == CallStatus.CONNECTING

                // Top-level destinations showing the bottom bar
                val isTopLevelDestination = currentRoute in listOf(
                    ROUTE_DISCOVERY,
                    ROUTE_CHATS,
                    ROUTE_TRANSFERS
                )

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (isTopLevelDestination) {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Hub,
                                            contentDescription = null,
                                            tint = ElectricCyan,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Text(
                                            text = "Mesh Messenger",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                        }
                    },
                    bottomBar = {
                        if (isTopLevelDestination) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 4.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentRoute == ROUTE_DISCOVERY,
                                    onClick = {
                                        navController.navigate(ROUTE_DISCOVERY) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.Radar, contentDescription = "Radar") },
                                    label = { Text("Radar") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        indicatorColor = ElectricCyan
                                    ),
                                    modifier = Modifier.testTag("nav_tab_discovery")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == ROUTE_CHATS,
                                    onClick = {
                                        navController.navigate(ROUTE_CHATS) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.ChatBubble, contentDescription = "Chats") },
                                    label = { Text("Chats") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        indicatorColor = ElectricCyan
                                    ),
                                    modifier = Modifier.testTag("nav_tab_chats")
                                )

                                NavigationBarItem(
                                    selected = currentRoute == ROUTE_TRANSFERS,
                                    onClick = {
                                        navController.navigate(ROUTE_TRANSFERS) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(Icons.Default.FolderZip, contentDescription = "Files") },
                                    label = { Text("Files") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Color.Black,
                                        indicatorColor = ElectricCyan
                                    ),
                                    modifier = Modifier.testTag("nav_tab_transfers")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (shouldShowCallScreen) {
                            VideoCallScreen(
                                viewModel = viewModel,
                                onEndCall = {
                                    viewModel.endCall()
                                }
                            )
                        } else {
                            NavHost(
                                navController = navController,
                                startDestination = ROUTE_DISCOVERY,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                composable(ROUTE_DISCOVERY) {
                                    DiscoveryScreen(
                                        viewModel = viewModel,
                                        onNavigateToChat = { peerId, peerName ->
                                            navController.navigate("chat_detail/$peerId/$peerName")
                                        }
                                    )
                                }

                                composable(ROUTE_CHATS) {
                                    ChatsScreen(
                                        viewModel = viewModel,
                                        onNavigateToChat = { peerId, peerName ->
                                            navController.navigate("chat_detail/$peerId/$peerName")
                                        }
                                    )
                                }

                                composable(ROUTE_TRANSFERS) {
                                    FileTransfersScreen(
                                        viewModel = viewModel
                                    )
                                }

                                composable(
                                    route = ROUTE_CHAT_DETAIL,
                                    arguments = listOf(
                                        navArgument("peerId") { type = NavType.StringType },
                                        navArgument("peerName") { type = NavType.StringType }
                                    )
                                ) { backStackEntry ->
                                    val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
                                    val peerName = backStackEntry.arguments?.getString("peerName") ?: "Peer"

                                    ChatDetailScreen(
                                        peerId = peerId,
                                        peerName = peerName,
                                        viewModel = viewModel,
                                        onBack = { navController.popBackStack() },
                                        onStartVideoCall = { pId, pName ->
                                            viewModel.startVideoCall(pId, pName)
                                        }
                                    )
                                }
                            }
                        }

                        // Incoming Call Dialog Overlay
                        if (callState.status == CallStatus.INCOMING_RINGING) {
                            IncomingCallDialog(
                                callerName = callState.peerName,
                                isNearby = callState.isNearbyChannel,
                                onAccept = { viewModel.acceptCall() },
                                onDecline = { viewModel.declineCall() }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IncomingCallDialog(
    callerName: String,
    isNearby: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Text(text = "Incoming Video Call", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "$callerName is calling you...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (isNearby) "Channel: Direct Nearby Link (Local zero data)" else "Channel: WebRTC Online (Internet)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isNearby) NeonEmerald else ElectricCyan
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("accept_call_button")
            ) {
                Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.Black)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Accept", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Button(
                onClick = onDecline,
                colors = ButtonDefaults.buttonColors(containerColor = RoseError),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("decline_call_button")
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White)
                Spacer(modifier = Modifier.size(6.dp))
                Text("Decline", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    )
}
