package org.monogram.feature.chats

import com.arkivanov.mvikotlin.core.store.Reducer
import org.monogram.core.database.dao.ChatReadState
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.LastSeen
import org.monogram.core.models.packTypingNames

internal object ChatsReducer : Reducer<ChatsStore.State, Msg> {
    override fun ChatsStore.State.reduce(msg: Msg): ChatsStore.State = when (msg) {
        is Msg.Loading -> copy(loading = msg.value)
        is Msg.Syncing -> copy(syncing = msg.value)
        is Msg.Chats -> copy(
            chats = if (msg.replace) msg.value else mergeChats(chats, msg.value),
            fromCache = msg.fromCache,
            error = if (msg.fromCache) error else null,
        )
        is Msg.Append -> copy(chats = mergeChats(chats, msg.value))
        is Msg.Query -> copy(
            query = msg.value,
            searchHasMore = false,
            searchError = null,
        )
        is Msg.LoadingMore -> copy(loadingMore = msg.value)
        is Msg.HasMore -> copy(hasMore = msg.value)
        is Msg.Error -> copy(error = msg.value)
        is Msg.Typing -> withUpdatedChat(msg.chatId.value) { chat ->
            chat.copy(
                typing = msg.typing,
                typingName = if (msg.typing) packTypingNames(msg.names) else null,
                typingAction = if (msg.typing) {
                    msg.action ?: ChatActionKind.Typing.wire
                } else {
                    null
                },
            )
        }
        is Msg.Status -> {
            val chat = chats.firstOrNull { it.id == msg.userId }
            if (chat == null ||
                !LastSeen.affectsUi(chat.peerStatus, chat.peerStatusAt, msg.status, msg.statusAt)
            ) {
                this
            } else {
                copy(
                    chats = chats.map {
                        if (it.id == msg.userId) {
                            it.copy(peerStatus = msg.status, peerStatusAt = msg.statusAt)
                        } else {
                            it
                        }
                    },
                )
            }
        }
        is Msg.EmojiStatus -> withUpdatedChat(msg.userId.value) {
            it.copy(emojiStatusDocumentId = msg.documentId)
        }
        is Msg.Self -> copy(self = msg.value)
        is Msg.Incoming -> copy(chats = applyIncomingMessage(chats, msg.value))
        is Msg.Edited -> copy(chats = applyEditedMessage(chats, msg.value))
        is Msg.LatestReplaced -> copy(
            chats = applyLatestReplacement(chats, msg.chatId, msg.value),
        )
        is Msg.ReadStates -> {
            val updated = applyReadStates(chats, msg.rows)
            if (updated === chats) this else copy(chats = updated)
        }
        is Msg.InboxRead -> {
            val updated = applyReadStates(chats, mapOf(msg.chatId.value to ChatReadState(msg.chatId.value, msg.maxId, msg.unread)))
            val cleared = if (msg.unread <= 0) {
                updated.map { chat ->
                    if (chat.id == msg.chatId && chat.unreadMark) chat.copy(unreadMark = false) else chat
                }
            } else {
                updated
            }
            if (cleared === chats) this else copy(chats = cleared)
        }
        is Msg.UnreadMark -> withUpdatedChat(msg.chatId.value) {
            it.copy(unreadMark = msg.value)
        }
        is Msg.OutboxRead -> withUpdatedChat(msg.chatId.value) {
            it.copy(readOutboxMaxId = msg.maxId)
        }
        is Msg.UnreadMentions -> copy(chats = applyUnreadMentions(chats, msg.chatId, msg.stillUnread))
        is Msg.UnreadReactions -> copy(chats = applyUnreadReactions(chats, msg.chatId, msg.stillUnread))
        is Msg.UnreadMentionsDelta -> copy(chats = applyUnreadMentionsDelta(chats, msg.chatId, msg.delta))
        is Msg.UnreadReactionsDelta -> copy(chats = applyUnreadReactionsDelta(chats, msg.chatId, msg.delta))
        is Msg.SearchLoading -> copy(
            searchLoading = msg.value,
            searchError = if (msg.value) null else searchError,
        )
        is Msg.SearchLoadingMore -> copy(searchLoadingMore = msg.value)
        is Msg.SearchError -> copy(
            searchError = msg.value,
            searchLoading = if (msg.value != null) false else searchLoading,
            searchLoadingMore = if (msg.value != null) false else searchLoadingMore,
            searchHasMore = if (msg.value != null) false else searchHasMore,
        )
        is Msg.SearchCleared -> copy(
            searchPeople = emptyList(),
            searchChats = emptyList(),
            searchMessages = emptyList(),
            searchHasMore = false,
            searchError = null,
            searchLoading = false,
            searchLoadingMore = false,
        )
        is Msg.SearchPage -> copy(
            searchPeople = if (msg.replace) msg.people else searchPeople,
            searchChats = if (msg.replace) msg.chats else searchChats,
            searchMessages = if (msg.replace) msg.messages else searchMessages + msg.messages,
            searchHasMore = msg.hasMore,
            searchError = if (msg.replace) null else searchError,
            searchLoading = false,
            searchLoadingMore = false,
        )
    }

    private inline fun ChatsStore.State.withUpdatedChat(
        peerId: Long,
        transform: (org.monogram.core.models.Chat) -> org.monogram.core.models.Chat,
    ): ChatsStore.State {
        val index = chats.indexOfFirst { it.id.value == peerId }
        if (index < 0) return this
        val previous = chats[index]
        val updated = transform(previous)
        if (updated == previous) return this
        val next = chats.toMutableList()
        next[index] = updated
        return copy(chats = next)
    }
}
