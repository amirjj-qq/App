package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ChatMessage
import com.example.model.MessageChannel
import com.example.model.PeerDevice
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import com.example.ui.theme.PurpleLight
import com.example.viewmodel.MessengerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsScreen(
    viewModel: MessengerViewModel,
    onNavigateToChat: (peerId: String, peerName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val connectedPeers by viewModel.connectedPeers.collectAsState()
    val persistentPeers by viewModel.persistentPeers.collectAsState()
    val allMessages by viewModel.allMessages.collectAsState()

    var selectedFilter by remember { mutableStateOf("All") }
    var showNewChatDialog by remember { mutableStateOf(false) }
    var targetPeerIdInput by remember { mutableStateOf("") }
    var targetPeerNameInput by remember { mutableStateOf("") }

    // Merge connected peers and persistent peers into unique chat items
    val chatPeers = remember(connectedPeers, persistentPeers) {
        val map = mutableMapOf<String, PeerDevice>()
        persistentPeers.forEach { map[it.id] = it }
        connectedPeers.forEach { (id, peer) -> map[id] = peer }
        map.values.toList()
    }

    // Filter peers
    val filteredPeers = remember(chatPeers, selectedFilter, connectedPeers) {
        when (selectedFilter) {
            "Nearby" -> chatPeers.filter { connectedPeers.containsKey(it.id) || it.isNearbyConnected }
            "Online" -> chatPeers.filter { !connectedPeers.containsKey(it.id) }
            else -> chatPeers
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All", "Nearby", "Online").forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricCyan.copy(alpha = 0.2f),
                            selectedLabelColor = ElectricCyan
                        )
                    )
                }
            }

            if (filteredPeers.isEmpty() && allMessages.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ChatBubbleOutline,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No Conversations Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Discover nearby peers on the Radar tab or start a direct chat with any Node ID.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPeers) { peer ->
                        val isDirect = connectedPeers.containsKey(peer.id)
                        val lastMsg = allMessages.firstOrNull { it.chatId == peer.id || it.senderId == peer.id }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToChat(peer.id, peer.name) }
                                .testTag("chat_card_${peer.id}"),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar with channel status badge
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(if (isDirect) NeonEmerald else PurpleLight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = peer.name.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp,
                                            color = Color.Black
                                        )
                                    }

                                    // Status pip
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isDirect) NeonEmerald else ElectricCyan
                                            )
                                            .align(Alignment.BottomEnd)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = peer.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        lastMsg?.let {
                                            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                                            Text(
                                                text = timeFormat.format(Date(it.timestamp)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = lastMsg?.content ?: "Tap to start conversation",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        // Channel Badge
                                        ChannelTag(
                                            channel = if (isDirect) MessageChannel.NEARBY_DIRECT else MessageChannel.INTERNET
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // FAB to start new chat
        FloatingActionButton(
            onClick = { showNewChatDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("new_chat_fab"),
            containerColor = ElectricCyan,
            contentColor = Color.Black
        ) {
            Icon(Icons.Default.Add, contentDescription = "New Chat")
        }
    }

    // New Chat Dialog
    if (showNewChatDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNewChatDialog = false },
            title = { Text("Start New Chat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = targetPeerNameInput,
                        onValueChange = { targetPeerNameInput = it },
                        label = { Text("Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = targetPeerIdInput,
                        onValueChange = { targetPeerIdInput = it },
                        label = { Text("Node ID or User ID") },
                        placeholder = { Text("e.g. NODE_8492 or remote user") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (targetPeerIdInput.isNotBlank()) {
                            val name = if (targetPeerNameInput.isNotBlank()) targetPeerNameInput else "Peer-${targetPeerIdInput.take(4)}"
                            showNewChatDialog = false
                            onNavigateToChat(targetPeerIdInput, name)
                        }
                    }
                ) {
                    Text("Start Chat")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChatDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ChannelTag(channel: MessageChannel) {
    val (label, bg, fg, icon) = when (channel) {
        MessageChannel.NEARBY_DIRECT -> Quad(
            "Nearby P2P",
            NeonEmerald.copy(alpha = 0.15f),
            NeonEmerald,
            Icons.Default.NearMe
        )
        MessageChannel.MESH_RELAY -> Quad(
            "Mesh Hop",
            ElectricCyan.copy(alpha = 0.15f),
            ElectricCyan,
            Icons.Default.Hub
        )
        MessageChannel.INTERNET -> Quad(
            "Internet",
            PurpleLight.copy(alpha = 0.15f),
            PurpleLight,
            Icons.Default.Cloud
        )
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Text(text = label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
