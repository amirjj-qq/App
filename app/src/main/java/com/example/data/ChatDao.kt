package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.model.ChatMessage
import com.example.model.MessageDeliveryStatus
import com.example.model.PeerDevice
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // --- Messages ---
    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestMessage(chatId: String): Flow<ChatMessage?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage)

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Query("UPDATE chat_messages SET deliveryStatus = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageDeliveryStatus)

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    suspend fun clearChat(chatId: String)

    // --- Peers ---
    @Query("SELECT * FROM peers ORDER BY lastSeenTimestamp DESC")
    fun getAllPeers(): Flow<List<PeerDevice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePeer(peer: PeerDevice)

    @Query("DELETE FROM peers WHERE id = :peerId")
    suspend fun deletePeer(peerId: String)
}
