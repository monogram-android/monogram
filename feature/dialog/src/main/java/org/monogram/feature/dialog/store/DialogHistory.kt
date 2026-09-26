package org.monogram.feature.dialog.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import org.monogram.feature.dialog.parsePinnedIds
import org.monogram.feature.dialog.pinnedMetaKey
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.SavedGifMemory
import org.monogram.feature.dialog.SenderTagMemory
import org.monogram.feature.dialog.StickerCatalogMemory
import org.monogram.feature.dialog.StickerPackMemory
import org.monogram.feature.dialog.HISTORY_PAGE_LIMIT
import org.monogram.feature.dialog.applyMessageEdit
import org.monogram.feature.dialog.contiguousOlderCache
import org.monogram.feature.dialog.dateJumpCacheWindow
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
    val messages = warmup?.let { cache ->
        if (cache.usesIoDispatcher) {
            withContext(Dispatchers.IO) { cache.messagesByIds(chatId, ids) }
        } else {
            cache.messagesByIds(chatId, ids)
        }
    }.orEmpty()
    if (messages.isEmpty()) return
    AppLog.api("dialog", "pinned cache chat=${chatId.value} count=${messages.size}")
    emit(Msg.Pinned(messages))
}

internal fun DialogExecutor.loadPinned() {
    if (pinnedRequested) return
    if (snapshot().pinnedMessages.isNotEmpty()) {
        pinnedRequested = true
        return
    }
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
        // Even an in-window jump supersedes an older network jump/pagination request.
        historyGen += 1
        olderRequestId++
        activeOlderRequestId = null
        newerRequestId++
        activeNewerRequestId = null
        emit(Msg.Loading(false))
        emit(Msg.LoadingOlder(false))
        emit(Msg.LoadingNewer(false))
        emit(Msg.Anchor(messageId, atTop = atTop))
        return
    }
    historyGen += 1
    val gen = historyGen
    cachedOlderTail = emptyList()
    olderRequestId++
    activeOlderRequestId = null
    newerRequestId++
    activeNewerRequestId = null
    emit(Msg.LoadingOlder(false))
    emit(Msg.LoadingNewer(false))
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
                // A jump replaces the visible window with a server page, so its
                // continuation must replace the prior window's network cursor too.
                val minId = result.value.minOfOrNull { it.id.id }
                serverHistoryBoundaryId = minId
                emit(
                    Msg.Messages(result.value, fromCache = false, replace = true),
                )
                // A centered jump page is not a newest-edge fetch: a short page still
                // has older history unless the server returned the true first message.
                emit(Msg.HasOlder(minId != null && !atHistoryOldest(minId)))
                // Anchor belongs to the committed jump window; publish it before any
                // suspending enrichment so an obsolete jump cannot overwrite a newer one.
                emit(Msg.Anchor(messageId, atTop = atTop))
                resolveSenders(result.value)
                warmup?.upsertMessages(result.value)
                sessionStore?.upsertPeerMins(result.value)
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
        isForum = snapshot().isForum,
    )
}

internal suspend fun DialogExecutor.topicOrHistoryPage(
    limit: Int = HISTORY_PAGE_LIMIT,
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
    generation: Int? = null,
) {
    val current = snapshot().messages
    val held = cachedOlderTail
    val paintWindow = {
        val source = if (liveEdge && (current.isNotEmpty() || held.isNotEmpty())) {
            mergeLiveEdgeMessages(current + held, incoming)
        } else {
            incoming
        }
        paintHistoryWindow(source)
    }
    val (paint, tail) = if (current.size + held.size + incoming.size <= 64) {
        paintWindow()
    } else {
        withContext(markupContext) { paintWindow() }
    }
    if (generation != null && generation != historyGen) return
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
    if (snapshot().loadingOlder) return
    if (snapshot().messages.isEmpty() && cachedOlderTail.isEmpty()) return
    if (snapshot().error?.requiresReauth == true) return
    emit(Msg.LoadingOlder(true, prefetch))
    if (cachedOlderTail.isNotEmpty()) {
        // The cache tail is leftover from the same paint source, but still apply the
        // continuity filter so a sparse first-page island cannot skip 15 days on scroll.
        val oldestVisible = snapshot().messages.minByOrNull { it.id.id }
        val cached = if (oldestVisible == null) {
            emptyList()
        } else {
            contiguousOlderCache(
                oldestVisible = oldestVisible,
                cachedOlder = cachedOlderTail,
                window = snapshot().messages + cachedOlderTail,
            )
        }
        cachedOlderTail = emptyList()
        if (cached.isNotEmpty()) {
            emit(Msg.AppendOlder(cached))
            emit(Msg.HasOlder(true))
        }
        if (prefetch && anchorToUnread && anchorUnreadIfNeeded()) {
            emit(Msg.LoadingOlder(false))
            return
        }
    }
    val oldest = snapshot().messages.minByOrNull { it.id.id } ?: run {
        emit(Msg.LoadingOlder(false))
        return
    }
    if (!snapshot().hasOlder) {
        emit(Msg.LoadingOlder(false))
        continueUnreadPaging()
        return
    }
    if (snapshot().error?.requiresReauth == true) {
        emit(Msg.LoadingOlder(false))
        return
    }
    // A local cache can contain ID 1 while missing the IDs between it and the
    // server page. Only a server-derived cursor may establish the true endpoint.
    if (serverHistoryBoundaryId?.let(::atHistoryOldest) == true) {
        emit(Msg.HasOlder(false))
        emit(Msg.LoadingOlder(false))
        continueUnreadPaging()
        AppLog.api("dialog", "older end chat=${chatId.value}")
        return
    }
    emit(Msg.LoadingOlder(true, prefetch))
    val gen = historyGen
    val requestId = ++olderRequestId
    activeOlderRequestId = requestId
    work.launch {
        fun ownsRequest() = gen == historyGen && activeOlderRequestId == requestId
        if (!ownsRequest()) return@launch
        val historyTop = ForumIo.historyThreadId(threadTopMsgId)
        val boundaryId = serverHistoryBoundaryId ?: run {
            emit(Msg.LoadingOlder(false))
            return@launch
        }
        if (!ownsRequest()) return@launch
        when (
            val result = if (historyTop > 0) {
                client.getReplies(
                    chatId,
                    historyTop,
                    HISTORY_PAGE_LIMIT,
                    offsetId = boundaryId,
                )
            } else {
                client.getHistoryPage(
                    chatId = chatId,
                    limit = HISTORY_PAGE_LIMIT,
                    offsetId = boundaryId,
                    addOffset = 0,
                )
            }
        ) {
            is Outcome.Ok -> {
                if (!ownsRequest()) return@launch
                val fresh = result.value.count { it.id.id < boundaryId }
                AppLog.api(
                    "dialog",
                    "network older chat=${chatId.value} count=${result.value.size} fresh=$fresh",
                )
                if (!ownsRequest()) return@launch
                emit(Msg.AppendOlder(result.value))
                serverHistoryBoundaryId = result.value.minOfOrNull { it.id.id } ?: boundaryId
                if (gen != historyGen) {
                    emit(Msg.LoadingOlder(false))
                    return@launch
                }
                emit(Msg.HasOlder(fresh > 0 && historyHasMore(result.value.size)))
                emit(Msg.LoadingOlder(false))
                activeOlderRequestId = null
                if (warmup?.usesIoDispatcher == true || sessionStore != null) {
                    withContext(Dispatchers.IO) {
                        warmup?.upsertMessages(result.value)
                        sessionStore?.upsertPeerMins(result.value)
                    }
                } else {
                    warmup?.upsertMessages(result.value)
                    sessionStore?.upsertPeerMins(result.value)
                }
                continueUnreadPaging()
                resolveSenders(result.value)
            }
            is Outcome.Err -> if (ownsRequest()) {
                // A failed page is not an end-of-history signal. Preserve the cursor and
                // keep the retry path available for transient network/offline failures.
                handleError(result.telegramError, false)
                emit(Msg.HasOlder(true))
                emit(Msg.LoadingOlder(false))
                activeOlderRequestId = null
            }
        }
    }
}

/**
 * Anchor a freshly opened history at the first unread message. [unreadAnchorId]
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
    val messages = snapshot().messages
    val group = messages.firstOrNull { it.id.id == id }?.groupedId
    pendingUnreadAnchorId = if (group != null) {
        messages.firstOrNull { it.groupedId == group }?.id?.id ?: id
    } else {
        id
    }
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
    val requestId = ++newerRequestId
    activeNewerRequestId = requestId
    emit(Msg.LoadingNewer(true))
    work.launch {
        fun ownsRequest() = gen == historyGen && activeNewerRequestId == requestId
        try {
            if (!ownsRequest()) return@launch
            when (
                val result = topicOrHistoryPage(
                    offsetId = newest.id.id,
                    addOffset = -HISTORY_PAGE_LIMIT,
                )
            ) {
                is Outcome.Ok -> {
                    if (!ownsRequest()) return@launch
                    emit(Msg.Prepend(result.value))
                    emit(Msg.HasNewer(historyHasMore(result.value.size)))
                    emit(Msg.LoadingNewer(false))
                    activeNewerRequestId = null
                    warmup?.upsertMessages(result.value)
                    sessionStore?.upsertPeerMins(result.value)
                    resolveSenders(result.value)
                }
                is Outcome.Err -> if (ownsRequest()) {
                    // A transient newer-page failure is retryable; it is not proof that the
                    // server has no newer messages.
                    handleError(result.telegramError, false)
                    emit(Msg.HasNewer(true))
                    emit(Msg.LoadingNewer(false))
                    activeNewerRequestId = null
                }
            }
        } finally {
            if (activeNewerRequestId == requestId) {
                activeNewerRequestId = null
                if (gen == historyGen) emit(Msg.LoadingNewer(false))
            }
        }
    }
}

internal fun DialogExecutor.jump(epochSeconds: Int) {
    // A date jump replaces the visible history window. Invalidate the old request and
    // continuation state before starting it so an in-flight older page cannot merge into
    // the new window and its cursor cannot be reused for the wrong date range.
    historyGen += 1
    val gen = historyGen
    serverHistoryBoundaryId = null
    cachedOlderTail = emptyList()
    olderRequestId++
    activeOlderRequestId = null
    newerRequestId++
    activeNewerRequestId = null
    emit(Msg.LoadingOlder(false))
    emit(Msg.LoadingNewer(false))
    emit(Msg.Loading(true))
    work.launch {
        val historyTop = ForumIo.historyThreadId(threadTopMsgId)
        if (historyTop <= 0) {
            val cached = warmup?.let { cache ->
                if (cache.usesIoDispatcher) {
                    withContext(Dispatchers.IO) {
                        cache.messagesBeforeDate(chatId, epochSeconds, HISTORY_PAGE_LIMIT)
                    }
                } else {
                    cache.messagesBeforeDate(chatId, epochSeconds, HISTORY_PAGE_LIMIT)
                }
            }.orEmpty()
            val paint = dateJumpCacheWindow(cached, epochSeconds)
            if (paint.isNotEmpty() && gen == historyGen) {
                emit(Msg.Messages(paint, fromCache = true, replace = true))
                emit(Msg.HasOlder(true))
                // Keep loading until the server jump page sets serverHistoryBoundaryId.
                // Clearing it here lets LoadOlder use a cache min as offset_id.
            }
        }
        when (
            val result = topicOrHistoryPage(offsetDate = epochSeconds)
        ) {
            is Outcome.Ok -> if (gen == historyGen) {
                val minId = result.value.minOfOrNull { it.id.id }
                serverHistoryBoundaryId = minId
                emit(
                    Msg.Messages(result.value, fromCache = false, replace = true),
                )
                emit(Msg.HasOlder(minId != null && !atHistoryOldest(minId)))
                warmup?.upsertMessages(result.value)
                sessionStore?.upsertPeerMins(result.value)
                resolveSenders(result.value)
            }
            is Outcome.Err -> if (gen == historyGen) handleError(result.telegramError, false)
        }
        if (gen == historyGen) emit(Msg.Loading(false))
    }
}

internal fun DialogExecutor.search(query: String) {
    emit(Msg.SearchQuery(query))
    searchJob?.cancel()
    if (query.isBlank()) {
        emit(Msg.Searching(false))
        searchJob = work.launch {
            delay(SEARCH_DEBOUNCE_MS.milliseconds)
            refresh()
        }
        return
    }
    // Search replaces the history window; invalidate every in-flight history operation
    // before the request starts so old pages cannot merge into search results.
    historyGen += 1
    val gen = historyGen
    olderRequestId++
    activeOlderRequestId = null
    newerRequestId++
    activeNewerRequestId = null
    emit(Msg.LoadingOlder(false))
    emit(Msg.LoadingNewer(false))
    emit(Msg.Loading(false))
    emit(Msg.Searching(true))
    searchJob = work.launch {
        try {
            delay(SEARCH_DEBOUNCE_MS.milliseconds)
            if (gen != historyGen || snapshot().searchQuery != query) return@launch
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
                    if (gen != historyGen || snapshot().searchQuery != query) return@launch
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
        } finally {
            // Cancellation must not strand the active query in the busy state. A
            // newer query owns the flag, so only the still-current request clears it.
            // A cancelled search coroutine is still in a cancelled context; use a
            // non-cancellable child so the terminal state cannot be dropped. Recheck
            // ownership inside the child so a replacement query keeps its busy state.
            withContext(NonCancellable) {
                if (gen == historyGen && snapshot().searchQuery == query) {
                    emit(Msg.Searching(false))
                }
            }
        }
    }
}
