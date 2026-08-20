package org.monogram.data.mtproto

import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.MtProtoTextMessageRepository

internal fun interface MtProtoClearHistoryRepository {
    suspend fun clear(chatIds: Set<Long>, revoke: Boolean)
}

internal class MtProtoClearHistoryRepositoryImpl(
    private val messages: MtProtoTextMessageRepository,
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoClearHistoryRepository {
    override suspend fun clear(chatIds: Set<Long>, revoke: Boolean) {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        chatIds.forEach { chatId ->
            val peer = if (chatId <= -1_000_000_000_001L) {
                val peerId = -(chatId + 1_000_000_000_000L)
                val chat = requireNotNull(chats.get(scope, peerId)) { "Missing MTProto chat projection: $peerId" }
                TelegramPeerChatId.decode(chatId, chat.type == MtProtoChatType.CHANNEL)
            } else {
                TelegramPeerChatId.decode(chatId)
            }
            messages.clearHistory(chatId, peer.type, revoke)
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
