package org.monogram.data.mtproto

import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MtProtoTextMessageRepository

internal fun interface MtProtoPinnedMessageRepository {
    suspend fun setPinned(chatId: Long, messageId: Long, pinned: Boolean)
}

internal class MtProtoPinnedMessageRepositoryImpl(
    private val dialogs: DialogSnapshotRepository,
    private val messages: MtProtoTextMessageRepository,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : MtProtoPinnedMessageRepository {
    override suspend fun setPinned(chatId: Long, messageId: Long, pinned: Boolean) {
        val dialog = requireNotNull(dialogs.getDialogs(accountId).firstOrNull {
            TelegramPeerChatId.encode(it.peerType, it.peerId) == chatId
        }) {
            "Missing MTProto dialog projection: $chatId"
        }
        messages.setPinned(chatId, dialog.peerType, messageId, pinned)
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
