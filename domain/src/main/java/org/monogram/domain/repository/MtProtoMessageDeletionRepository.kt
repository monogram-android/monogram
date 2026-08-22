package org.monogram.domain.repository

import org.monogram.domain.models.DialogPeerType

interface MtProtoMessageDeletionRepository {
    suspend fun delete(chatId: Long, peerType: DialogPeerType, messageIds: List<Long>, revoke: Boolean)
}
