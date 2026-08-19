package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.ChatSearchRepository
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.domain.repository.SearchMessagesResult

internal class MtProtoChatSearchRepository(
    private val dialogRepository: DialogSnapshotRepository,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatSearchRepository {
    override val searchHistory: Flow<List<ChatModel>> = emptyFlow()

    override suspend fun searchChats(query: String): List<ChatModel> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return emptyList()
        return dialogRepository.getDialogs(accountId)
            .asSequence()
            .filter { it.matches(normalized) }
            .mapNotNull { it.toChatModel() }
            .toList()
    }

    override suspend fun searchPublicChats(query: String): List<ChatModel> =
        unsupported("MTProto public chat search is not available")

    override suspend fun searchMessages(query: String, offset: String, limit: Int): SearchMessagesResult =
        unsupported("MTProto message search is not available")

    override fun addSearchChatId(chatId: Long) = unsupported("MTProto search history is not available")
    override fun removeSearchChatId(chatId: Long) = unsupported("MTProto search history is not available")
    override fun clearSearchHistory() = unsupported("MTProto search history is not available")

    private fun DialogSnapshotModel.matches(query: String): Boolean =
        title.orEmpty().lowercase().contains(query) || username.orEmpty().lowercase().contains(query)

    private fun DialogSnapshotModel.toChatModel() = takeIf {
        isPeerResolved && !isPeerDeleted && !isPeerForbidden && peerType != DialogPeerType.UNKNOWN
    }?.let {
        ChatModel(
            id = TelegramPeerChatId.encode(peerType, peerId),
            title = title?.takeIf(String::isNotBlank) ?: username.orEmpty().ifBlank { peerId.toString() },
            unreadCount = unreadCount,
            lastMessageText = latestMessage.text.orEmpty(),
            lastMessageDate = latestMessage.date,
            lastMessageId = latestMessage.messageId,
            isLastMessageOutgoing = latestMessage.isOutgoing,
            messageSenderId = latestMessage.senderId,
            lastMessageContentType = if (latestMessage.hasMedia) "media" else "text",
            username = username,
            type = when (peerType) {
                DialogPeerType.PRIVATE -> ChatType.PRIVATE
                DialogPeerType.BASIC_GROUP -> ChatType.BASIC_GROUP
                DialogPeerType.SUPERGROUP,
                DialogPeerType.CHANNEL -> ChatType.SUPERGROUP
                DialogPeerType.UNKNOWN -> return@let null
            },
            isGroup = peerType != DialogPeerType.PRIVATE,
            isSupergroup = peerType == DialogPeerType.SUPERGROUP || peerType == DialogPeerType.CHANNEL,
            isChannel = peerType == DialogPeerType.CHANNEL,
        )
    }

    private fun unsupported(message: String): Nothing = throw UnsupportedOperationException(message)

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
