package com.example.data

import com.example.model.ChatMessage
import com.example.model.MessageDeliveryStatus
import com.example.model.PeerDevice
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    fun getMessagesForChat(chatId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForChat(chatId)
    }

    fun getAllMessages(): Flow<List<ChatMessage>> {
        return chatDao.getAllMessages()
    }

    fun getAllPeers(): Flow<List<PeerDevice>> {
        return chatDao.getAllPeers()
    }

    suspend fun saveMessage(message: ChatMessage) {
        chatDao.insertMessage(message)
    }

    suspend fun updateMessageStatus(messageId: String, status: MessageDeliveryStatus) {
        chatDao.updateMessageStatus(messageId, status)
    }

    suspend fun insertOrUpdatePeer(peer: PeerDevice) {
        chatDao.insertOrUpdatePeer(peer)
    }

    suspend fun clearChat(chatId: String) {
        chatDao.clearChat(chatId)
    }
}
