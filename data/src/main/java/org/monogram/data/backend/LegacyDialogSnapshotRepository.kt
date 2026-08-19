package org.monogram.data.backend

import org.drinkless.tdlib.TdApi
import org.monogram.data.chats.ChatCache
import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.DialogSnapshotRepository

internal class LegacyDialogSnapshotRepository(
    private val accessGuard: LegacyBackendAccessGuard,
    private val chatListRepository: ChatListRepository,
    private val chatCache: ChatCache,
) : DialogSnapshotRepository {
    override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> {
        accessGuard.requireAccess(accountId)
        return chatListRepository.chatListFlow.value.map { it.toSnapshot() }
    }

    private fun ChatModel.toSnapshot() = chatCache.getChat(id)?.let { chat ->
        val peer = chat.type.toPeer(id)
        DialogSnapshotModel(
            peerId = peer.id,
            peerType = peer.type,
            title = title,
            username = username,
            isPeerResolved = peer.isResolved,
            isPeerDeleted = false,
            isPeerForbidden = false,
            latestMessage = toMessagePreview(),
        )
    } ?: DialogSnapshotModel(
        peerId = id,
        peerType = DialogPeerType.UNKNOWN,
        title = title,
        username = username,
        isPeerResolved = false,
        isPeerDeleted = false,
        isPeerForbidden = false,
        latestMessage = toMessagePreview(),
    )

    private fun ChatModel.toMessagePreview() = DialogMessagePreviewModel(
        messageId = lastMessageId,
        senderId = messageSenderId,
        date = lastMessageDate,
        text = lastMessageText.ifEmpty { null },
        isService = false,
        isDeleted = false,
        isOutgoing = isLastMessageOutgoing,
        hasMedia = lastMessageContentType in MEDIA_CONTENT_TYPES,
    )

    private fun TdApi.ChatType.toPeer(chatId: Long): Peer = when (this) {
        is TdApi.ChatTypePrivate -> Peer(DialogPeerType.PRIVATE, userId.takeIf { it != 0L } ?: chatId, userId != 0L)
        is TdApi.ChatTypeBasicGroup -> Peer(DialogPeerType.BASIC_GROUP, basicGroupId.takeIf { it != 0L } ?: chatId, basicGroupId != 0L)
        is TdApi.ChatTypeSupergroup -> Peer(
            if (isChannel) DialogPeerType.CHANNEL else DialogPeerType.SUPERGROUP,
            supergroupId.takeIf { it != 0L } ?: chatId,
            supergroupId != 0L,
        )
        is TdApi.ChatTypeSecret -> Peer(DialogPeerType.UNKNOWN, chatId, false)
        else -> Peer(DialogPeerType.UNKNOWN, chatId, false)
    }

    private data class Peer(val type: DialogPeerType, val id: Long, val isResolved: Boolean)

    private companion object {
        val MEDIA_CONTENT_TYPES = setOf(
            "photo",
            "video",
            "voice",
            "video_note",
            "sticker",
            "document",
            "audio",
            "gif",
        )
    }
}
