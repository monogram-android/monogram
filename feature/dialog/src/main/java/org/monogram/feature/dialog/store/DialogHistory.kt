package org.monogram.feature.dialog.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
import org.monogram.feature.dialog.parsePinnedIds
import org.monogram.feature.dialog.pinnedMetaKey
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerPackMemory
import org.monogram.feature.dialog.HISTORY_PAGE_LIMIT
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.mergeLiveEdgeMessages
import org.monogram.feature.dialog.paintHistoryWindow
import org.monogram.feature.dialog.atHistoryOldest
import org.monogram.feature.dialog.historyHasMore
import org.monogram.feature.dialog.historyPagingAllowed
import org.monogram.feature.dialog.isTransientSendFailure
import org.monogram.feature.dialog.jumpNeedsFetch
import org.monogram.feature.dialog.nextPinnedIndex
import org.monogram.feature.dialog.unreadAnchorId
import org.monogram.feature.dialog.localMediaCacheKey
import org.monogram.feature.dialog.mergeSenderTags
import org.monogram.feature.dialog.parseUpdateMessageId
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun DialogExecutor.hydrateSenderTags() {
    if (snapshot().senderTags.isNotEmpty()) return
    val ram = org.monogram.feature.dialog.SenderTagMemory.get(chatId.value)
    if (ram.isNotEmpty()) {
        emit(Msg.SenderTags(ram))
        return
    }
    val parsed = org.monogram.feature.dialog.parseSenderTags(
        sessionStore?.readMeta(org.monogram.feature.dialog.tagsMetaKey(chatId.value)),
    )
    if (parsed.isEmpty()) return
    org.monogram.feature.dialog.SenderTagMemory.put(chatId.value, parsed)
    emit(Msg.SenderTags(parsed))
}

internal suspend fun DialogExecutor.hydratePinned() {
    if (snapshot().pinnedMessages.isNotEmpty()) return
    val ids = parsePinnedIds(sessionStore?.readMeta(pinnedMetaKey(chatId.value)))
    if (ids.isEmpty()) return
    val messages = withContext(Dispatchers.IO) {
        warmup?.messagesByIds(chatId, ids).orEmpty()
    }
    if (messages.isEmpty()) return
    AppLog.api("dialog", "pinned cache chat=${chatId.value} count=${messages.size}")
    emit(Msg.Pinned(messages))
}

internal fun DialogExecutor.loadPinned() {
    if (pinnedRequested) return
    pinnedRequested = true
    work.launch {
        when (val result = client.getPinnedMessages(chatId)) {
            is Outcome.Ok -> {
                AppLog.api("dialog", "pinned network chat=${chatId.value} count=${result.value.size}")
                emit(Msg.Pinned(result.value))
                rememberPinned()
            }
            is Outcome.Err -> Unit
        }
    }
}

internal fun DialogExecutor.nextPinned() {
    val current = snapshot()
    if (current.pinnedMessages.isEmpty()) {
        loadPinned()
        return
    }
    var index = current.pinnedIndex.coerceIn(0, current.pinnedMessages.lastIndex)
    if (current.anchorMessageId == current.pinnedMessages[index].id.id &&
        current.pinnedMessages.size > 1
    ) {
        index = nextPinnedIndex(index, current.pinnedMessages.size)
        emit(Msg.PinnedIndex(index))
        rememberPinned()
    }
    jumpToPinned(current.pinnedMessages[index].id.id)
}

internal fun DialogExecutor.jumpToPinned(messageId: Int) {
    val current = snapshot()
    val index = current.pinnedMessages.indexOfFirst { it.id.id == messageId }
    if (index >= 0) {
        emit(Msg.PinnedIndex(index))
        rememberPinned()
    }
    emit(Msg.PinnedListOpen(false))
    jumpToMessageId(messageId)
}

internal fun DialogExecutor.jumpToMessage(messageId: Int, atTop: Boolean = false) {
    if (messageId <= 0) return
    jumpToMessageId(messageId, atTop = atTop)
}

internal fun DialogExecutor.jumpToMessageId(messageId: Int, atTop: Boolean = false) {
    if (!jumpNeedsFetch(snapshot().messages, messageId)) {
        emit(Msg.Anchor(messageId, atTop = atTop))
        return
    }
    historyGen += 1
    val gen = historyGen
    emit(Msg.Loading(true))
    work.launch {
        when (
            val result = topicOrHistoryPage(
                offsetId = messageId,
                addOffset = -20,
            )
        ) {
            is Outcome.Ok -> {
                if (gen != historyGen) return@launch
                emit(
                    Msg.Messages(result.value, fromCache = false, replace = true),
                )
                resolveSenders(result.value)
                warmup?.upsertMessages(result.value)
                sessionStore?.upsertPeerMins(result.value)
                emit(Msg.Anchor(messageId, atTop = atTop))
            }
            is Outcome.Err -> if (gen == historyGen) {
                handleError(result.telegramError, false)
            }
        }
        if (gen == historyGen) emit(Msg.Loading(false))
    }
}

internal fun DialogExecutor.inCommentThread(message: Message): Boolean {
    return messageBelongsToThread(
        message = message,
        threadTopMsgId = threadTopMsgId,
        knownMessages = snapshot().messages + message,
    )
}

internal suspend fun DialogExecutor.topicOrHistoryPage(
    limit: Int = 40,
    offsetId: Int = 0,
    offsetDate: Int = 0,
    addOffset: Int = 0,
): Outcome<List<Message>> {
    val historyTop = ForumIo.historyThreadId(threadTopMsgId)
    return if (historyTop > 0) {
        client.getReplies(chatId, historyTop, limit, offsetId, addOffset)
    } else {
        client.getHistoryPage(
            chatId = chatId,
            limit = limit,
            offsetId = offsetId,
            offsetDate = offsetDate,
            addOffset = addOffset,
        )
    }
}

internal suspend fun DialogExecutor.publishPaintedHistory(
    incoming: List<Message>,
    fromCache: Boolean,
    liveEdge: Boolean,
) {
    val current = snapshot().messages
    val held = cachedOlderTail
    val (paint, tail) = withContext(Dispatchers.Default) {
        val source = if (liveEdge && (current.isNotEmpty() || held.isNotEmpty())) {
            mergeLiveEdgeMessages(current + held, incoming)
        } else {
            incoming
        }
        paintHistoryWindow(source)
    }
    cachedOlderTail = tail
    val sameWindow = current.size == paint.size &&
        current.indices.all { index ->
            val old = current[index]
            val next = paint[index]
            old.id.id == next.id.id &&
                old.text == next.text &&
                old.mediaCacheKey == next.mediaCacheKey &&
                old.mediaKind == next.mediaKind
        }
    if (!sameWindow) {
        emit(
            Msg.Messages(
                paint,
                fromCache = fromCache,
                replace = !fromCache,
                liveEdge = liveEdge,
            ),
        )
    }
    if (tail.isNotEmpty()) emit(Msg.HasOlder(true))
}

internal fun DialogExecutor.loadOlder(prefetch: Boolean = false) {
    if (cachedOlderTail.isNotEmpty()) {
        if (snapshot().loadingOlder) return
        emit(Msg.LoadingOlder(true, prefetch))
        val (next, rest) = paintHistoryWindow(cachedOlderTail)
        cachedOlderTail = rest
        emit(Msg.AppendOlder(next))
        emit(Msg.HasOlder(true))
        work.launch {
            delay(100)
            emit(Msg.LoadingOlder(false))
        }
        return
    }
    val oldest = snapshot().messages.minByOrNull { it.id.id } ?: return
    if (snapshot().loadingOlder || !snapshot().hasOlder) return
    if (snapshot().error?.requiresReauth == true) return
    if (atHistoryOldest(oldest.id.id)) {
        emit(Msg.HasOlder(false))
        AppLog.api("dialog", "older end chat=${chatId.value}")
        return
    }
    emit(Msg.LoadingOlder(true, prefetch))
    val gen = historyGen
    work.launch {
        if (gen != historyGen) {
            emit(Msg.LoadingOlder(false))
            return@launch
        }
        val historyTop = ForumIo.historyThreadId(threadTopMsgId)
        val cachedOlder = if (historyTop > 0) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                warmup?.olderMessages(chatId, oldest.id.id, HISTORY_PAGE_LIMIT).orEmpty()
            }
        }
        if (cachedOlder.isNotEmpty()) {
            AppLog.api("dialog", "cache older chat=${chatId.value} count=${cachedOlder.size}")
            emit(Msg.AppendOlder(cachedOlder))
            yield()
        }
        when (
            val result = if (historyTop > 0) {
                client.getReplies(
                    chatId,
                    historyTop,
                    HISTORY_PAGE_LIMIT,
                    offsetId = oldest.id.id,
                )
            } else {
                client.getHistoryPage(
                    chatId = chatId,
                    limit = HISTORY_PAGE_LIMIT,
                    offsetId = oldest.id.id,
                    addOffset = 0,
                )
            }
        ) {
            is Outcome.Ok -> {
                if (gen != historyGen) {
                    emit(Msg.LoadingOlder(false))
                    return@launch
                }
                // Compare with the requested boundary: the same page may already
                // have been appended from cache while the request was in flight.
                val fresh = result.value.count { it.id.id < oldest.id.id }
                AppLog.api(
                    "dialog",
                    "network older chat=${chatId.value} count=${result.value.size} fresh=$fresh",
                )
                emit(Msg.AppendOlder(result.value))
                yield()
                resolveSenders(result.value)
                withContext(Dispatchers.IO) {
                    warmup?.upsertMessages(result.value)
                    sessionStore?.upsertPeerMins(result.value)
                }
                emit(
                    Msg.HasOlder(
                        fresh > 0 && (
                            historyHasMore(result.value.size) ||
                                cachedOlder.size >= HISTORY_PAGE_LIMIT
                            ),
                    ),
                )
            }
            is Outcome.Err -> {
                handleError(result.telegramError, false)
                emit(Msg.HasOlder(false))
            }
        }
        emit(Msg.LoadingOlder(false))
    }
}

/**
 * Anchor a freshly opened history at the first unread message. [firstUnreadAnchorId]
 * only answers once the boundary is provably inside the loaded window, so the caller can
 * keep paging older history until it is.
 */

internal fun DialogExecutor.anchorUnreadIfNeeded(): Boolean {
    if (!anchorToUnread || snapshot().anchorMessageId != null) return true
    if (snapshot().unreadCount <= 0) {
        anchorToUnread = false
        return true
    }
    val id = unreadAnchorId(
        messages = snapshot().messages,
        unreadCount = snapshot().unreadCount,
        readInboxMaxId = snapshot().readInboxMaxId,
        hasOlder = snapshot().hasOlder,
        hasNewer = snapshot().hasNewer,
    ) ?: return false
    rememberUnreadAnchor(id)
    AppLog.api("dialog", "anchor unread chat=${chatId.value} id=$id")
    emit(Msg.Anchor(id, atTop = true))
    return true
}

internal fun DialogExecutor.rememberUnreadAnchor(id: Int) {
    anchorToUnread = false
    pendingUnreadAnchorId = id
}

/** Pull older pages until the unread boundary is loaded, then anchor there. */

internal fun DialogExecutor.continueUnreadPaging() {
    if (!anchorToUnread || snapshot().anchorMessageId != null) return
    if (anchorUnreadIfNeeded()) return
    if (!snapshot().hasOlder) {
        // History ended before the boundary: anchor at the oldest message we have.
        snapshot().messages.minByOrNull { it.id.id }?.let { oldest ->
            rememberUnreadAnchor(oldest.id.id)
            AppLog.api("dialog", "anchor oldest chat=${chatId.value} id=${oldest.id.id}")
            emit(Msg.Anchor(oldest.id.id, atTop = true))
        } ?: run { anchorToUnread = false }
        return
    }
    work.launch {
        delay(32)
        if (!anchorToUnread || snapshot().anchorMessageId != null) return@launch
        if (snapshot().loadingOlder) return@launch
        loadOlder(prefetch = true)
    }
}

internal fun DialogExecutor.handleError(error: TelegramError, showIfEmpty: Boolean) {
    AppLog.warn("dialog", error.logLine())
    if (error.kind == TelegramError.Kind.FileReference) {
        emit(Msg.Error(error))
        refresh()
        return
    }
    if (showIfEmpty || snapshot().messages.isNotEmpty()) {
        emit(Msg.Error(error))
    }
}

internal fun DialogExecutor.loadNewer() {
    val newest = snapshot().messages.maxByOrNull { it.id.id } ?: return
    if (snapshot().loadingNewer || !snapshot().hasNewer) return
    if (snapshot().searchQuery.isNotBlank()) return
    val gen = historyGen
    emit(Msg.LoadingNewer(true))
    work.launch {
        when (
            val result = topicOrHistoryPage(
                offsetId = newest.id.id,
                addOffset = -40,
            )
        ) {
            is Outcome.Ok -> {
                if (gen != historyGen) return@launch
                emit(Msg.Prepend(result.value))
                resolveSenders(result.value)
                warmup?.upsertMessages(result.value)
                sessionStore?.upsertPeerMins(result.value)
                val added = result.value.count { it.id.id > newest.id.id }
                emit(Msg.HasNewer(historyHasMore(added)))
            }
            is Outcome.Err -> if (gen == historyGen) {
                handleError(result.telegramError, false)
                emit(Msg.HasNewer(false))
            }
        }
        if (gen == historyGen) emit(Msg.LoadingNewer(false))
    }
}

internal fun DialogExecutor.jump(epochSeconds: Int) {
    emit(Msg.Loading(true))
    work.launch {
        when (
            val result = topicOrHistoryPage(offsetDate = epochSeconds)
        ) {
            is Outcome.Ok -> {
                emit(
                    Msg.Messages(result.value, fromCache = false, replace = true),
                )
                resolveSenders(result.value)
                warmup?.upsertMessages(result.value)
                sessionStore?.upsertPeerMins(result.value)
            }
            is Outcome.Err -> handleError(result.telegramError, false)
        }
        emit(Msg.Loading(false))
    }
}

internal fun DialogExecutor.search(query: String) {
    emit(Msg.SearchQuery(query))
    searchJob?.cancel()
    if (query.isBlank()) {
        searchJob = work.launch {
            delay(SEARCH_DEBOUNCE_MS.milliseconds)
            refresh()
        }
        return
    }
    emit(Msg.Searching(true))
    searchJob = work.launch {
        delay(SEARCH_DEBOUNCE_MS.milliseconds)
        if (snapshot().searchQuery != query) return@launch
        val historyTop = ForumIo.historyThreadId(threadTopMsgId)
        val result = if (historyTop > 0) {
            when (val replies = client.getReplies(chatId, historyTop, 100)) {
                is Outcome.Ok -> Outcome.Ok(
                    replies.value.filter {
                        it.text.orEmpty().contains(query, ignoreCase = true)
                    },
                )
                is Outcome.Err -> replies
            }
        } else {
            client.searchMessages(chatId, query)
        }
        when (result) {
            is Outcome.Ok -> {
                if (snapshot().searchQuery != query) return@launch
                emit(
                    Msg.Messages(
                        result.value,
                        fromCache = false,
                        replace = true,
                        searchHit = true,
                    ),
                )
                resolveSenders(result.value)
            }
            is Outcome.Err -> handleError(result.telegramError, true)
        }
        emit(Msg.Searching(false))
    }
}
