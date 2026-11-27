package org.monogram.feature.dialog.store

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.feature.dialog.encodeForumTopics
import org.monogram.feature.dialog.forgetEvictedDialogMeta
import org.monogram.feature.dialog.parseForumTopics
import org.monogram.feature.dialog.topicsMetaKey

internal fun DialogExecutor.probesTopics(cachedChat: Chat?): Boolean =
    cachedChat?.isForum == true || snapshot().isForum || (forumUnknown && cachedChat == null && threadTopMsgId <= 0 && ForumIo.isChannelPeer(
        chatId,
    ))

internal fun DialogExecutor.loadTopicHeader() {
    val topicId = threadTopMsgId
    if (topicId <= 0 || topicHeaderJob?.isActive == true) return
    topicHeaderJob = work.launch {
        unreadMutex.withLock {
            when (val result = client.getForumTopicsById(chatId, listOf(topicId))) {
                is Outcome.Ok -> {
                    val topic = result.value.topics.firstOrNull { it.id == topicId }
                    if (topic != null) {
                        emit(
                            Msg.TopicHeader(
                                title = topic.title,
                                iconColor = topic.iconColor,
                                iconEmojiId = topic.iconEmojiId,
                            ),
                        )
                        emit(Msg.TopicClosed(topic.closed))
                        emit(Msg.UnreadCount(topic.unreadCount))
                        applyUnreadCounters(
                            topic.unreadMentionsCount, topic.unreadReactionsCount
                        )
                        emit(
                            Msg.ReadInbox(
                                maxOf(
                                    snapshot().readInboxMaxId, topic.readInboxMaxId
                                )
                            )
                        )
                    }
                }

                is Outcome.Err -> {
                    if (!isNotForumError(result.telegramError)) {
                        handleError(result.telegramError, false)
                    }
                }
            }
        }
    }
}

internal fun DialogExecutor.isNotForumError(error: TelegramError): Boolean {
    val type = error.type.uppercase()
    return type.contains("CHANNEL_FORUM_MISSING") || type.contains("CHANNEL_MONOFORUM")
}

internal suspend fun DialogExecutor.persistForumFlag(isForum: Boolean) {
    val stored = warmup?.chats().orEmpty().firstOrNull { it.id == chatId } ?: return
    if (stored.isForum == isForum) return
    warmup?.upsertChats(listOf(stored.copy(isForum = isForum)))
}

private suspend fun DialogExecutor.hydrateTopics(gen: Int) {
    if (snapshot().topics.isNotEmpty()) return
    val ram = memory.topics.get(chatId.value)
    if (ram.isNotEmpty()) {
        emit(Msg.Topics(ram, ram.size, append = false))
        return
    }
    val parsed = parseForumTopics(
        sessionStore?.readMeta(topicsMetaKey(chatId.value)),
    )
    if (gen != historyGen || parsed.isEmpty() || snapshot().topics.isNotEmpty()) return
    memory.topics.put(chatId.value, parsed)
    AppLog.api("dialog", "topics cache chat=${chatId.value} count=${parsed.size}")
    emit(Msg.Topics(parsed, parsed.size, append = false))
}

internal fun DialogExecutor.loadTopics(
    reset: Boolean,
    gen: Int = historyGen,
) {
    work.launch {
        if (gen != historyGen) return@launch
        if (reset) {
            if (topicsLoadingGen == gen) return@launch
            topicsLoadingGen = gen
        }
        try {
            emit(Msg.Error(null))
            if (reset) {
                hydrateTopics(gen)
                if (gen != historyGen) return@launch
                if (snapshot().topics.isNotEmpty()) emit(Msg.Loading(false))
            }
            if (snapshot().topics.isEmpty()) emit(Msg.LoadingTopics(true))
            val last = if (reset) {
                null
            } else {
                snapshot().topics.let { topics ->
                    topics.lastOrNull { !it.pinned } ?: topics.lastOrNull()
                }
            }
            AppLog.api("dialog", "getForumTopics start chat=${chatId.value}")
            val result = client.getForumTopics(
                chatId,
                offsetDate = last?.date ?: 0,
                offsetId = last?.topMessageId ?: 0,
                offsetTopic = last?.id ?: 0,
            )
            if (gen != historyGen) return@launch
            when (result) {
                is Outcome.Ok -> {
                    val page = result.value
                    forumUnknown = false
                    emit(Msg.IsForum(true))
                    persistForumFlag(true)
                    if (gen != historyGen) return@launch
                    emit(Msg.Topics(page.topics, page.count, append = !reset))
                    val loaded =
                        if (reset) page.topics.size else snapshot().topics.size + page.topics.size
                    emit(Msg.HasMoreTopics(loaded < page.count && page.topics.isNotEmpty()))
                    if (reset) {
                        memory.topics.put(chatId.value, page.topics)
                        sessionStore?.writeMeta(
                            topicsMetaKey(chatId.value),
                            encodeForumTopics(page.topics),
                        )
                    }
                }

                is Outcome.Err -> {
                    if (isNotForumError(result.telegramError)) {
                        confirmedNonForum = true
                        forumUnknown = false
                        emit(Msg.IsForum(false))
                        emit(Msg.Topics(emptyList(), 0, append = false))
                        emit(Msg.HasMoreTopics(false))
                        emit(Msg.LoadingTopics(false))
                        persistForumFlag(false)
                        if (gen == historyGen) refresh()
                        return@launch
                    }
                    handleError(result.telegramError, reset && snapshot().topics.isEmpty())
                }
            }
        } finally {
            val ownsReset = reset && topicsLoadingGen == gen
            if (ownsReset) topicsLoadingGen = -1
            if (gen == historyGen && (ownsReset || topicsLoadingGen != gen)) {
                emit(Msg.LoadingTopics(false))
                emit(Msg.Loading(false))
            }
        }
    }
}

internal fun DialogExecutor.loadMoreTopics() {
    if (snapshot().loadingTopics || !snapshot().hasMoreTopics) return
    loadTopics(reset = false)
}

internal fun DialogExecutor.toggleTopicHidden(
    topicId: Int,
    hidden: Boolean,
) {
    if (!snapshot().canManageTopics) return
    val previous = snapshot().topics
    val current = previous.firstOrNull { it.id == topicId } ?: return
    if (current.hidden == hidden) return
    val next = previous.map { topic ->
        if (topic.id != topicId) {
            topic
        } else {
            topic.copy(hidden = hidden, closed = if (hidden) true else topic.closed)
        }
    }
    emit(Msg.Topics(next, snapshot().topicsCount, append = false))
    work.launch {
        when (val result = client.editForumTopicHidden(chatId, topicId, hidden)) {
            is Outcome.Ok -> {
                persistForumTopics(next)
            }

            is Outcome.Err -> {
                persistForumTopics(previous)
                emit(Msg.Topics(previous, snapshot().topicsCount, append = false))
                handleError(result.telegramError, false)
            }
        }
    }
}

internal suspend fun DialogExecutor.persistForumTopics(topics: List<ForumTopic>) {
    val evicted = memory.topics.put(chatId.value, topics)
    forgetEvictedDialogMeta(sessionStore, evicted, ::topicsMetaKey)
    sessionStore?.writeMeta(
        topicsMetaKey(chatId.value),
        encodeForumTopics(topics),
    )
}
