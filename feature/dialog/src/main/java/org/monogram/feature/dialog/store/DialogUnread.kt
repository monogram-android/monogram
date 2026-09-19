package org.monogram.feature.dialog.store

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.feature.dialog.unreadJumpAddOffset
import org.monogram.network.bridge.MtprotoUpdate

internal fun DialogExecutor.applyUnreadCountersFromChat(
    mentions: Int,
    reactions: Int,
) {
    if (threadTopMsgId > 0) {
        if (snapshot().inForumTopic) loadTopicHeader()
        return
    }
    applyUnreadCounters(mentions, reactions)
}

internal fun DialogExecutor.applyUnreadCounters(mentions: Int, reactions: Int) {
    val current = snapshot()
    // Equal totals can refer to different messages after reading on another device.
    invalidateUnreadPages()
    if (current.unreadMentionsCount != mentions) emit(Msg.UnreadMentionsCount(mentions))
    if (current.unreadReactionsCount != reactions) emit(Msg.UnreadReactionsCount(reactions))
    if (mentions > 0) unreadMentionsExhausted = false
    if (reactions > 0) unreadReactionsExhausted = false
    consumeVisibleUnread(visibleUnreadIds)
}

internal fun DialogExecutor.jumpMention() {
    if (mentionJumpBusy) return
    mentionJumpBusy = true
    work.launch {
        try {
            unreadMutex.withLock {
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
                    return@withLock
                }
                jumpToMessage(id, atTop = true)
            }
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
            unreadMutex.withLock {
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
                    return@withLock
                }
                jumpToMessage(id, atTop = true)
            }
        } finally {
            reactionJumpBusy = false
        }
    }
}

internal fun DialogExecutor.consumeVisibleUnread(messageIds: Set<Int>) {
    visibleUnreadIds = messageIds.filter { it > 0 }.toSet()
    if (visibleUnreadIds.isEmpty() || unreadConsumeJob?.isActive == true) return
    unreadConsumeJob = work.launch {
        do {
            val visible = visibleUnreadIds
            val revision = unreadRevision
            unreadMutex.withLock {
                val mentions = if (snapshot().unreadMentionsCount > 0) {
                    visibleUnread(visible) { offset ->
                        client.getUnreadMentions(chatId, offsetId = offset, limit = 100, topMsgId = threadTopMsgId)
                    }
                } else emptySet()
                val reactions = if (snapshot().unreadReactionsCount > 0) {
                    visibleUnread(visible) { offset ->
                        client.getUnreadReactions(chatId, offsetId = offset, limit = 100, topMsgId = threadTopMsgId)
                    }
                } else emptySet()
                if (revision != unreadRevision) return@withLock
                val ids = mentions + reactions
                if (ids.isNotEmpty()) {
                    // Reading contents acknowledges only these messages, never the whole dialog.
                    when (val result = client.readMessageContents(chatId, ids.toList())) {
                        is Outcome.Ok -> {
                            unreadMentionIds.removeAll(ids)
                            unreadReactionIds.removeAll(ids)
                            // A server snapshot received during the RPC already owns the counters.
                            if (revision == unreadRevision) {
                                emit(Msg.UnreadMentionsCount(snapshot().unreadMentionsCount - mentions.size))
                                emit(Msg.UnreadReactionsCount(snapshot().unreadReactionsCount - reactions.size))
                                warmup?.addUnreadMentions(chatId, -mentions.size)
                                warmup?.addUnreadReactions(chatId, -reactions.size)
                            }
                        }
                        is Outcome.Err -> {
                            handleError(result.telegramError, false)
                            return@launch
                        }
                    }
                }
            }
        } while (visible != visibleUnreadIds || revision != unreadRevision)
    }
}

private suspend fun visibleUnread(
    visible: Set<Int>,
    fetch: suspend (Int) -> Outcome<List<Message>>,
): Set<Int> {
    val oldest = visible.minOrNull() ?: return emptySet()
    var offset = visible.maxOrNull()!!.let { if (it == Int.MAX_VALUE) 0 else it + 1 }
    val found = mutableSetOf<Int>()
    while (true) {
        val page = when (val result = fetch(offset)) {
            is Outcome.Ok -> result.value.map { it.id.id }.filter { it > 0 }
            is Outcome.Err -> return emptySet()
        }
        found += page.filter { it in visible }
        val next = page.minOrNull() ?: break
        if (page.size < 100 || next <= oldest || (offset != 0 && next >= offset)) break
        offset = next
    }
    return found
}

internal suspend fun DialogExecutor.markMentionsCaughtUp() {
    unreadMentionIds.clear()
    unreadMentionsExhausted = true
    emit(Msg.UnreadMentionsCount(0))
    if (threadTopMsgId <= 0) warmup?.applyUnreadMentions(chatId, 0)
}

internal suspend fun DialogExecutor.markReactionsCaughtUp() {
    unreadReactionIds.clear()
    unreadReactionsExhausted = true
    emit(Msg.UnreadReactionsCount(0))
    if (threadTopMsgId <= 0) warmup?.applyUnreadReactions(chatId, 0)
}

internal fun DialogExecutor.invalidateUnreadPages() {
    unreadRevision++
    unreadMentionIds.clear()
    unreadReactionIds.clear()
    unreadMentionsExhausted = false
    unreadReactionsExhausted = false
}
internal fun DialogExecutor.handleUnreadCounterUpdate(update: MtprotoUpdate) {
    val target = when (update) {
        is MtprotoUpdate.UnreadMentions -> update.chatId
        is MtprotoUpdate.UnreadReactions -> update.chatId
        is MtprotoUpdate.UnreadMentionsDelta -> update.chatId
        is MtprotoUpdate.UnreadReactionsDelta -> update.chatId
        else -> return
    }
    if (target != chatId) return
    invalidateUnreadPages()
    if (threadTopMsgId > 0) {
        if (snapshot().inForumTopic) loadTopicHeader()
        return
    }
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
    consumeVisibleUnread(visibleUnreadIds)
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
    val revision = unreadRevision
    when (val result = fetch(firstOffset)) {
        is Outcome.Ok -> {
            if (revision != unreadRevision) return null
            ids += result.value.map { it.id.id }.filter { it > 0 }
        }
        is Outcome.Err -> return null
    }
    if (ids.isEmpty() && firstOffset != 0) {
        when (val retry = fetch(0)) {
            is Outcome.Ok -> {
                if (revision != unreadRevision) return null
                ids += retry.value.map { it.id.id }.filter { it > 0 }
            }
            is Outcome.Err -> return null
        }
    }
    if (ids.isEmpty()) markExhausted(true)
    return ids.minOrNull()
}
