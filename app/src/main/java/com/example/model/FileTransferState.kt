package com.example.model

enum class TransferStatus {
    WAITING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELED
}

data class FileTransferState(
    val transferId: Long,             // Nearby payloadId or upload task hash
    val peerId: String,
    val peerName: String,
    val fileName: String,
    val totalBytes: Long = 0L,
    val bytesTransferred: Long = 0L,
    val isIncoming: Boolean = true,
    val status: TransferStatus = TransferStatus.WAITING,
    val filePathOrUri: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val formattedSize: String
        get() = when {
            totalBytes >= 1024 * 1024 -> String.format("%.1f MB", totalBytes / (1024f * 1024f))
            totalBytes >= 1024 -> String.format("%.1f KB", totalBytes / 1024f)
            else -> "$totalBytes B"
        }
}
