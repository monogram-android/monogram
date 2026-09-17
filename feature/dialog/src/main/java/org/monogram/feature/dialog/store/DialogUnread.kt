package org.monogram.feature.dialog.store

import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.feature.dialog.unreadJumpAddOffset
import org.monogram.network.bridge.MtprotoUpdate

internal fun DialogExecutor.applyUnreadCountersFromChat(
    mentions: Int,
    reactions: Int,
) {
    val current = snapshot()
    if (current.unreadMentionsCount != mentions) emit(Msg.UnreadMentionsCount(mentions))
    if (current.unreadReactionsCount != reactions) emit(Msg.UnreadReactionsCount(reactions))
    if (mentions > 0) unreadMentionsExhausted = false
    if (reactions > 0) unreadReactionsExhausted = false
}

internal fun DialogExecutor.jumpMention() {
    if (mentionJumpBusy) return
    mentionJumpBusy = true
    work.launch {
        try {
            val id = nextUnreadId(
                ids = unreadMentionIds,
                exhausted = { unreadMentionsExhausted },
                markExhausted = { unreadMentionsExhausted = it },
                count = { snapshot().unreadMentionsCount },
                fetch = { offset ->
                    client.getUnreadMentions(
                        chatId = chatId,
                        addOffset = offset,
                        limit = 100,
                        topMsgId = threadTopMsgId,
                    )
                },
            )
            if (id == null) {
                if (unreadMentionsExhausted && unreadMentionIds.isEmpty()) markMentionsCaughtUp()
                return@launch
            }
            jumpToMessage(id, atTop = true)
        } finally {
            mentionJumpBusy = false
        }
    }
}

internal fun DialogExecutor.jumpUnreadReaction() {
    if (reactionJumpBusy) return
    reactionJumpBusy = true
    work.launch {
        try {
            val id = nextUnreadId(
                ids = unreadReactionIds,
                exhausted = { unreadReactionsExhausted },
                markExhausted = { unreadReactionsExhausted = it },
                count = { snapshot().unreadReactionsCount },
                fetch = { offset ->
                    client.getUnreadReactions(
                        chatId = chatId,
                        addOffset = offset,
                        limit = 100,
                        topMsgId = threadTopMsgId,
                    )
                },
            )
            if (id == null) {
                if (unreadReactionsExhausted && unreadReactionIds.isEmpty()) markReactionsCaughtUp()
                return@launch
            }
            jumpToMessage(id, atTop = true)
        } finally {
            reactionJumpBusy = false
        }
    }
}

internal fun DialogExecutor.consumeVisibleUnread(messageIds: Set<Int>) {
    if (messageIds.isEmpty()) return
    work.launch {
        if (snapshot().unreadMentionsCount > 0 && unreadMentionIds.isEmpty() && !unreadMentionsExhausted) {
            nextUnreadId(
                ids = unreadMentionIds,
                exhausted = { unreadMentionsExhausted },
                markExhausted = { unreadMentionsExhausted = it },
                count = { snapshot().unreadMentionsCount },
                fetch = { offset ->
                    client.getUnreadMentions(
                        chatId = chatId,
                        addOffset = offset,
                        limit = 100,
                        topMsgId = threadTopMsgId,
                    )
                },
            )
        }
        if (snapshot().unreadReactionsCount > 0 && unreadReactionIds.isEmpty() && !unreadReactionsExhausted) {
            nextUnreadId(
                ids = unreadReactionIds,
                exhausted = { unreadReactionsExhausted },
                markExhausted = { unreadReactionsExhausted = it },
                count = { snapshot().unreadReactionsCount },
                fetch = { offset ->
                    client.getUnreadReactions(
                        chatId = chatId,
                        addOffset = offset,
                        limit = 100,
                        topMsgId = threadTopMsgId,
                    )
                },
            )
        }
        val mentionsChanged = unreadMentionIds.removeAll(messageIds)
        val reactionsChanged = unreadReactionIds.removeAll(messageIds)
        if (mentionsChanged) {
            emit(Msg.UnreadMentionsCount(unreadMentionIds.size))
            persistMentionCount(unreadMentionIds.size)
            if (unreadMentionIds.isEmpty()) markMentionsCaughtUp()
        }
        if (reactionsChanged) {
            emit(Msg.UnreadReactionsCount(unreadReactionIds.size))
            persistReactionCount(unreadReactionIds.size)
            if (unreadReactionIds.isEmpty()) markReactionsCaughtUp()
        }
    }
}

internal suspend fun DialogExecutor.markMentionsCaughtUp() {
    unreadMentionIds.clear()
    unreadMentionsExhausted = true
    emit(Msg.UnreadMentionsCount(0))
    persistMentionCount(0)
    client.readMentions(chatId, threadTopMsgId)
}

internal suspend fun DialogExecutor.markReactionsCaughtUp() {
    unreadReactionIds.clear()
    unreadReactionsExhausted = true
    emit(Msg.UnreadReactionsCount(0))
    persistReactionCount(0)
    client.readReactions(chatId, threadTopMsgId)
}

internal fun DialogExecutor.handleUnreadCounterUpdate(update: MtprotoUpdate) {
    when (update) {
        is MtprotoUpdate.UnreadMentions -> if (update.chatId == chatId) {
            emit(Msg.UnreadMentionsCount(update.stillUnread))
            if (update.stillUnread > 0) unreadMentionsExhausted = false
        }
        is MtprotoUpdate.UnreadReactions -> if (update.chatId == chatId) {
            emit(Msg.UnreadReactionsCount(update.stillUnread))
            if (update.stillUnread > 0) unreadReactionsExhausted = false
        }
        is MtprotoUpdate.UnreadMentionsDelta -> if (update.chatId == chatId) {
            emit(Msg.UnreadMentionsCount(snapshot().unreadMentionsCount + update.delta))
            if (update.delta > 0) unreadMentionsExhausted = false
        }
        is MtprotoUpdate.UnreadReactionsDelta -> if (update.chatId == chatId) {
            emit(Msg.UnreadReactionsCount(snapshot().unreadReactionsCount + update.delta))
            if (update.delta > 0) unreadReactionsExhausted = false
        }
        else -> Unit
    }
}

private suspend fun DialogExecutor.nextUnreadId(
    ids: MutableSet<Int>,
    exhausted: () -> Boolean,
    markExhausted: (Boolean) -> Unit,
    count: () -> Int,
    fetch: suspend (addOffset: Int) -> Outcome<List<Message>>,
): Int? {
    ids.minOrNull()?.let { return it }
    if (exhausted()) return null
    val firstOffset = unreadJumpAddOffset(count())
    when (val result = fetch(firstOffset)) {
        is Outcome.Ok -> ids += result.value.map { it.id.id }.filter { it > 0 }
        is Outcome.Err -> return null
    }
    if (ids.isEmpty() && firstOffset != 0) {
        when (val retry = fetch(0)) {
            is Outcome.Ok -> ids += retry.value.map { it.id.id }.filter { it > 0 }
            is Outcome.Err -> return null
        }
    }
    if (ids.isEmpty()) markExhausted(true)
    return ids.minOrNull()
}

private suspend fun DialogExecutor.persistMentionCount(count: Int) {
    val chat = warmup?.chat(chatId) ?: return
    warmup.upsertChats(listOf(chat.copy(unreadMentionsCount = count)))
}

private suspend fun DialogExecutor.persistReactionCount(count: Int) {
    val chat = warmup?.chat(chatId) ?: return
    warmup.upsertChats(listOf(chat.copy(unreadReactionsCount = count)))
}
