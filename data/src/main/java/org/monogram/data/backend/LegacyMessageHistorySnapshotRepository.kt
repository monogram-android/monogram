package org.monogram.data.backend

import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageHistoryCursorModel
import org.monogram.domain.models.MessageHistorySnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.models.MessageModel
import org.monogram.domain.repository.ConversationKey
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.domain.repository.MessageRepository

internal class LegacyMessageHistorySnapshotRepository(
    private val accessGuard: LegacyBackendAccessGuard,
    private val messageRepository: MessageRepository,
    private val chatCache: ChatCache,
) : MessageHistorySnapshotRepository {
    override suspend fun getHistory(request: MessageHistorySnapshotRequest): MessageHistorySnapshotPage {
        accessGuard.requireAccess(request.accountId)
        require(request.limit in 1..MAX_PAGE_SIZE) {
            "History page size must be between 1 and $MAX_PAGE_SIZE"
        }
        require(request.peerId > 0L) { "History peer id must be positive" }
        request.before?.let { cursor ->
            require(cursor.date >= 0) { "History cursor date must not be negative" }
            require(cursor.messageId != 0L) { "History cursor message id must not be zero" }
        }

        val chatId = resolveChatId(request.peerType, request.peerId)
        val page = messageRepository.getHistoryPage(
            HistoryRequest(
                key = ConversationKey(chatId),
                anchor = request.before?.let { HistoryAnchor.Message(it.messageId) } ?: HistoryAnchor.Latest,
                direction = if (request.before == null) HistoryDirection.Initial else HistoryDirection.Older,
                limit = request.limit,
            )
        )
        return MessageHistorySnapshotPage(
            messages = page.messages.map { it.toSnapshot() },
            nextCursor = page.messages.lastOrNull()
                ?.takeIf { page.messages.size == request.limit }
                ?.let { MessageHistoryCursorModel(it.date, it.id) },
        )
    }

    private fun resolveChatId(peerType: DialogPeerType, peerId: Long): Long {
        require(peerType != DialogPeerType.UNKNOWN) { "Cannot load history for an unknown peer type" }
        val indexedChatId = when (peerType) {
            DialogPeerType.PRIVATE -> chatCache.userIdToChatId[peerId]
            DialogPeerType.BASIC_GROUP -> chatCache.basicGroupIdToChatId[peerId]
            DialogPeerType.SUPERGROUP,
            DialogPeerType.CHANNEL -> chatCache.supergroupIdToChatId[peerId]
            DialogPeerType.UNKNOWN -> null
        }
        return sequenceOf(indexedChatId?.let(chatCache::getChat))
            .plus(chatCache.allChats.values.asSequence())
            .filterNotNull()
            .firstOrNull { it.type.matches(peerType, peerId) }
            ?.id
            ?: error("Legacy TDLib chat is not available for the requested peer")
    }

    private fun TdApi.ChatType.matches(peerType: DialogPeerType, peerId: Long): Boolean = when (this) {
        is TdApi.ChatTypePrivate -> peerType == DialogPeerType.PRIVATE && userId == peerId
        is TdApi.ChatTypeBasicGroup -> peerType == DialogPeerType.BASIC_GROUP && basicGroupId == peerId
        is TdApi.ChatTypeSupergroup -> supergroupId == peerId && when (peerType) {
            DialogPeerType.SUPERGROUP -> !isChannel
            DialogPeerType.CHANNEL -> isChannel
            else -> false
        }
        else -> false
    }

    private fun MessageModel.toSnapshot() = MessageHistorySnapshotModel(
        messageId = id,
        senderId = senderId.takeIf { it != 0L },
        date = date,
        text = content.snapshotText(),
        isService = content is MessageContent.Service,
        isDeleted = false,
        isOutgoing = isOutgoing,
        isMentioned = hasUnreadMention,
        isMediaUnread = false,
        isSilent = false,
        isPinned = isPinned,
        editDate = editDate.takeIf { it != 0 },
        groupedId = mediaAlbumId.takeIf { it != 0L },
        hasMedia = content.hasSnapshotMedia(),
    )

    private fun MessageContent.snapshotText(): String? = when (this) {
        is MessageContent.Text -> text
        is MessageContent.Service -> text
        is MessageContent.Photo -> caption
        is MessageContent.Video -> caption
        is MessageContent.Document -> caption
        is MessageContent.Audio -> caption
        is MessageContent.Gif -> caption
        is MessageContent.Poll -> question
        is MessageContent.Venue -> title
        is MessageContent.Checklist -> title
        is MessageContent.PaidMedia -> caption
        else -> null
    }?.takeIf { it.isNotBlank() }

    private fun MessageContent.hasSnapshotMedia() = when (this) {
        is MessageContent.Photo,
        is MessageContent.Video,
        is MessageContent.Voice,
        is MessageContent.VideoNote,
        is MessageContent.Document,
        is MessageContent.Audio,
        is MessageContent.Sticker,
        is MessageContent.Gif,
        is MessageContent.PaidMedia -> true
        else -> false
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
