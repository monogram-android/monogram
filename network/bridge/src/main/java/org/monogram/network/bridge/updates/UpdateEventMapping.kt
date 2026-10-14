package org.monogram.network.bridge.updates

import org.monogram.core.common.AppLog
import org.monogram.core.models.PeerId
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.chat.toChatModels
import uniffi.monogram_mtproto.UpdateEventDto
import org.monogram.network.bridge.message.toModel as toMessageModel

/**
 * Domain updates for one native event. `ChatsChanged` and `FoldersChanged` only
 * request a refresh, so they map to nothing and the caller schedules the fetch.
 */
internal fun UpdateEventDto.toMtprotoUpdates(): List<MtprotoUpdate> = when (val event = this) {
    is UpdateEventDto.ChatsChanged, is UpdateEventDto.FoldersChanged -> emptyList()

    is UpdateEventDto.NewMessage -> {
        AppLog.api("updates", "newMessage chat=${event.message.chatId} id=${event.message.id}")
        listOf(MtprotoUpdate.NewMessage(event.message.toMessageModel()))
    }

    is UpdateEventDto.MessageEdited -> {
        AppLog.api("updates", "messageEdited chat=${event.message.chatId} id=${event.message.id}")
        listOf(MtprotoUpdate.MessageEdited(event.message.toMessageModel()))
    }

    is UpdateEventDto.MessagesDeleted -> listOf(
        MtprotoUpdate.MessagesDeleted(
            chatId = event.chatId?.let(::PeerId),
            messageIds = event.messageIds,
        ),
    )

    is UpdateEventDto.PeerTyping -> listOf(
        MtprotoUpdate.PeerTyping(
            chatId = PeerId(event.chatId),
            userId = PeerId(event.userId),
            typing = event.typing,
            action = event.action.ifBlank { if (event.typing) "typing" else "" },
        ),
    )

    is UpdateEventDto.PeerEmojiStatus -> listOf(
        MtprotoUpdate.PeerEmojiStatus(userId = PeerId(event.userId), documentId = event.documentId),
    )

    is UpdateEventDto.PeerStatus -> listOf(
        MtprotoUpdate.PeerStatus(
            userId = PeerId(event.userId),
            status = event.status,
            statusAt = event.statusAt,
        ),
    )

    is UpdateEventDto.ReadInbox -> {
        AppLog.api(
            "read-state",
            "server chat=${event.chatId} max=${event.maxId} unread=${event.stillUnread}"
        )
        listOf(
            MtprotoUpdate.ReadInbox(
                chatId = PeerId(event.chatId),
                maxId = event.maxId,
                stillUnread = event.stillUnread,
            ),
        )
    }

    is UpdateEventDto.ReadOutbox -> listOf(
        MtprotoUpdate.ReadOutbox(chatId = PeerId(event.chatId), maxId = event.maxId),
    )

    is UpdateEventDto.Ignored -> ignoredUpdates(event.kind)

    is UpdateEventDto.SavedGifsChanged -> listOf(MtprotoUpdate.SavedGifsChanged)

    is UpdateEventDto.MessageReactions -> listOf(
        MtprotoUpdate.MessageReactions(
            chatId = PeerId(event.chatId),
            messageId = event.messageId,
            reactionsJson = event.reactionsJson,
        ),
    )

    is UpdateEventDto.DiscussionInbox -> listOf(
        MtprotoUpdate.DiscussionInbox(
            channelId = PeerId(event.channelId),
            topMessageId = event.topMessageId,
            readMaxId = event.readMaxId,
        ),
    )

    is UpdateEventDto.DialogsPatched ->
        if (event.chats.isEmpty()) {
            emptyList()
        } else {
            listOf(MtprotoUpdate.ChatsChanged(event.chats.toChatModels(assignPinOrder = false)))
        }

    is UpdateEventDto.SyncState -> listOf(MtprotoUpdate.SyncState(event.isSyncing))
}

private fun ignoredUpdates(kind: String): List<MtprotoUpdate> {
    val parts = kind.split(':')
    return when {
        kind.startsWith("dialog_unread_mark:") -> {
            val chatId = parts.getOrNull(1)?.toLongOrNull()
            val unread = parts.getOrNull(2) == "1"
            listOfNotNull(chatId?.let { MtprotoUpdate.DialogUnreadMark(PeerId(it), unread) })
        }

        kind.startsWith("notify_settings:") -> {
            val peerKind = parts.getOrNull(1).orEmpty()
            val chatId = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val muteUntil = parts.getOrNull(3)?.toIntOrNull() ?: 0
            if (peerKind.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    MtprotoUpdate.NotifySettingsChanged(
                        peerKind = peerKind,
                        chatId = PeerId(chatId),
                        muteUntil = muteUntil,
                        topicId = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
                    ),
                )
            }
        }

        kind.startsWith("UpdateMessageId:") -> {
            AppLog.api("updates", "message id mapped")
            listOf(MtprotoUpdate.Ignored(kind))
        }

        kind.startsWith("unread_mentions_delta:") -> {
            val chatId = parts.getOrNull(1)?.toLongOrNull()
            val delta = parts.getOrNull(2)?.toIntOrNull() ?: 1
            listOfNotNull(chatId?.let { MtprotoUpdate.UnreadMentionsDelta(PeerId(it), delta) })
        }

        kind.startsWith("unread_reactions_delta:") -> {
            val chatId = parts.getOrNull(1)?.toLongOrNull()
            val delta = parts.getOrNull(2)?.toIntOrNull() ?: 1
            listOfNotNull(chatId?.let { MtprotoUpdate.UnreadReactionsDelta(PeerId(it), delta) })
        }

        else -> {
            AppLog.api("updates", "ignored $kind")
            listOf(MtprotoUpdate.Ignored(kind))
        }
    }
}
