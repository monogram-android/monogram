package org.monogram.data.mtproto

import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MtProtoMessageDeletionRepository

internal fun interface MtProtoDeleteMessageRepository {
    suspend fun delete(chatId: Long, messageIds: List<Long>, revoke: Boolean)
}

internal class MtProtoDeleteMessageRepositoryImpl(
    private val dialogs: DialogSnapshotRepository,
    private val deletion: MtProtoMessageDeletionRepository,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : MtProtoDeleteMessageRepository {
    override suspend fun delete(chatId: Long, messageIds: List<Long>, revoke: Boolean) {
        val dialog = requireNotNull(dialogs.getDialogs(accountId).firstOrNull {
            TelegramPeerChatId.encode(it.peerType, it.peerId) == chatId
        }) { "Missing MTProto dialog projection: $chatId" }
        deletion.delete(chatId, dialog.peerType, messageIds, revoke)
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
