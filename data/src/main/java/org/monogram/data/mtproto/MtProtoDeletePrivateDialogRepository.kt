package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.MtProtoTextMessageRepository

internal fun interface MtProtoDeletePrivateDialogRepository {
    suspend fun delete(chatIds: Set<Long>)
}

internal class MtProtoDeletePrivateDialogRepositoryImpl(
    private val dialogs: DialogSnapshotRepository,
    private val messages: MtProtoTextMessageRepository,
    private val dialogStore: MtProtoDialogStore,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : MtProtoDeletePrivateDialogRepository {
    override suspend fun delete(chatIds: Set<Long>) {
        val projected = dialogs.getDialogs(accountId).associateBy {
            TelegramPeerChatId.encode(it.peerType, it.peerId)
        }
        val targets = chatIds.map { chatId ->
            requireNotNull(projected[chatId]) { "Missing MTProto dialog projection: $chatId" }.also {
                require(it.peerType == DialogPeerType.PRIVATE) {
                    "MTProto dialog deletion is only supported for private chats"
                }
            }
        }
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        targets.forEach { dialog ->
            val chatId = TelegramPeerChatId.encode(dialog.peerType, dialog.peerId)
            messages.clearHistory(chatId, DialogPeerType.PRIVATE, revoke = true)
            dialogStore.delete(scope, MtProtoMessagePeerType.USER, dialog.peerId)
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
