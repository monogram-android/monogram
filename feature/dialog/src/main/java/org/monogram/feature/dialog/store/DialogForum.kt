package org.monogram.feature.dialog.store

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.Chat
import org.monogram.core.models.ChatActionKind
import org.monogram.core.models.ForumIo
import org.monogram.core.models.ForumTopic
import org.monogram.core.models.InlineBotResult
import org.monogram.core.models.InlineBotResults
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ReactionChoice
import org.monogram.core.models.ReadReceiptConfig
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkups
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.core.models.StyledText
import org.monogram.core.models.TextEntities
import org.monogram.core.models.TextEntity
import org.monogram.core.models.TypingPresence
import org.monogram.core.models.UploadItem
import org.monogram.core.models.canShowMessageViewers
import org.monogram.core.models.canShowOutboxReadDate
import org.monogram.core.models.geoPlace
import org.monogram.core.models.isPlaceholderPeerTitle
import org.monogram.core.models.peerAvatarCacheKey
import org.monogram.core.models.playedMediaKind
import org.monogram.core.models.preferredPeerTitle
import org.monogram.feature.dialog.ComposerAt
import org.monogram.feature.dialog.ComposerAtToken
import org.monogram.feature.dialog.ComposerPanels
import org.monogram.feature.dialog.DialogStore
import org.monogram.feature.dialog.DraftMention
import org.monogram.feature.dialog.InlineBotQuery
import org.monogram.feature.dialog.MentionCandidate
import org.monogram.feature.dialog.PinnedBarMemory
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerPackMemory
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isTransientSendFailure
import org.monogram.feature.dialog.jumpNeedsFetch
import org.monogram.feature.dialog.localMediaCacheKey
import org.monogram.feature.dialog.mergeSenderTags
import org.monogram.feature.dialog.parseUpdateMessageId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlin.time.Duration.Companion.milliseconds

internal fun DialogExecutor.probesTopics(cachedChat: Chat?): Boolean =
    cachedChat?.isForum == true ||
        snapshot().isForum ||
        (
            forumUnknown &&
                cachedChat == null &&
                threadTopMsgId <= 0 &&
                ForumIo.isChannelPeer(chatId)
            )

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
                        applyUnreadCounters(topic.unreadMentionsCount, topic.unreadReactionsCount)
                        emit(Msg.ReadInbox(maxOf(snapshot().readInboxMaxId, topic.readInboxMaxId)))
                    }
                }
                is Outcome.Err -> if (!isNotForumError(result.telegramError)) {
                    handleError(result.telegramError, false)
                }
            }
        }
    }
}

internal fun DialogExecutor.isNotForumError(error: TelegramError): Boolean {
    val type = error.type.uppercase()
    return type.contains("CHANNEL_FORUM_MISSING") ||
        type.contains("CHANNEL_MONOFORUM")
}

internal suspend fun DialogExecutor.persistForumFlag(isForum: Boolean) {
    val stored = warmup?.chats().orEmpty().firstOrNull { it.id == chatId } ?: return
    if (stored.isForum == isForum) return
    warmup?.upsertChats(listOf(stored.copy(isForum = isForum)))
}

internal suspend fun DialogExecutor.hydrateTopics() {
    if (snapshot().topics.isNotEmpty()) return
    val ram = org.monogram.feature.dialog.TopicListMemory.get(chatId.value)
    if (ram.isNotEmpty()) {
        emit(Msg.Topics(ram, ram.size, append = false))
        return
    }
    val parsed = org.monogram.feature.dialog.parseForumTopics(
        sessionStore?.readMeta(org.monogram.feature.dialog.topicsMetaKey(chatId.value)),
    )
    if (parsed.isEmpty()) return
    org.monogram.feature.dialog.TopicListMemory.put(chatId.value, parsed)
    AppLog.api("dialog", "topics cache chat=${chatId.value} count=${parsed.size}")
    emit(Msg.Topics(parsed, parsed.size, append = false))
}

internal fun DialogExecutor.loadTopics(reset: Boolean, gen: Int = historyGen) {
    emit(Msg.Error(null))
    work.launch {
        if (reset) hydrateTopics()
        if (snapshot().topics.isEmpty()) emit(Msg.LoadingTopics(true))
        val offsetDate: Int
        val offsetId: Int
        val offsetTopic: Int
        if (reset) {
            offsetDate = 0
            offsetId = 0
            offsetTopic = 0
        } else {
            val last = snapshot().topics.lastOrNull { !it.pinned } ?: snapshot().topics.lastOrNull()
            offsetDate = last?.date ?: 0
            offsetId = last?.topMessageId ?: 0
            offsetTopic = last?.id ?: 0
        }
        AppLog.api("dialog", "getForumTopics start chat=${chatId.value}")
        when (
            val result = client.getForumTopics(
                chatId,
                offsetDate = offsetDate,
                offsetId = offsetId,
                offsetTopic = offsetTopic,
            )
        ) {
            is Outcome.Ok -> {
                if (gen != historyGen) return@launch
                val page = result.value
                forumUnknown = false
                emit(Msg.IsForum(true))
                persistForumFlag(true)
                emit(Msg.Topics(page.topics, page.count, append = !reset))
                if (reset) {
                    org.monogram.feature.dialog.TopicListMemory.put(chatId.value, page.topics)
                    sessionStore?.writeMeta(
                        org.monogram.feature.dialog.topicsMetaKey(chatId.value),
                        org.monogram.feature.dialog.encodeForumTopics(page.topics),
                    )
                }
                val loaded = if (reset) page.topics.size else snapshot().topics.size + page.topics.size
                emit(Msg.HasMoreTopics(loaded < page.count && page.topics.isNotEmpty()))
            }
            is Outcome.Err -> if (gen == historyGen) {
                if (isNotForumError(result.telegramError)) {
                    confirmedNonForum = true
                    forumUnknown = false
                    emit(Msg.IsForum(false))
                    emit(Msg.Topics(emptyList(), 0, append = false))
                    emit(Msg.HasMoreTopics(false))
                    emit(Msg.LoadingTopics(false))
                    persistForumFlag(false)
                    refresh()
                    return@launch
                }
                handleError(result.telegramError, reset && snapshot().topics.isEmpty())
            }
        }
        if (gen == historyGen) {
            emit(Msg.LoadingTopics(false))
            emit(Msg.Loading(false))
        }
    }
}

internal fun DialogExecutor.loadMoreTopics() {
    if (snapshot().loadingTopics || !snapshot().hasMoreTopics) return
    loadTopics(reset = false)
}
