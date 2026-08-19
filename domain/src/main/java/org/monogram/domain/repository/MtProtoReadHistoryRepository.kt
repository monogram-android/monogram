package org.monogram.domain.repository

import org.monogram.domain.models.DialogPeerType

interface MtProtoReadHistoryRepository {
    suspend fun markRead(chatId: Long, peerType: DialogPeerType, maxMessageId: Long)
}
