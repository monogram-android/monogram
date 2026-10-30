package org.monogram.feature.dialog.store

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.models.ForumIo
import org.monogram.core.models.MessageViewers
import org.monogram.core.models.OutboxReadState
import org.monogram.core.models.OutboxReadState.Loading
import org.monogram.core.models.canShowMessageViewers
import org.monogram.core.models.canShowOutboxReadDate
import org.monogram.core.models.playedMediaKind
import org.monogram.core.models.poll
import org.monogram.feature.dialog.READ_RECEIPT_DEBOUNCE_MS
import org.monogram.feature.dialog.ViewerPageState
import org.monogram.feature.dialog.readReceiptTarget
import org.monogram.feature.dialog.suppressLiveEdgeRead

internal fun DialogExecutor.loadReactionUsers(messageId: Int) = loadViewerPage(messageId, false)

internal fun DialogExecutor.loadPollVoters(messageId: Int) = loadViewerPage(messageId, true)

private fun DialogExecutor.loadViewerPage(messageId: Int, poll: Boolean) {
    val current = snapshot()
    if (!current.canView || messageId <= 0) return
    val message =
        (current.messages + current.pinnedMessages).firstOrNull { it.id.id == messageId && it.id.chatId == chatId }
            ?: return
    if (poll && message.poll?.publicVoters != true) return
    val page = (if (poll) current.pollVoterPages else current.reactionPages)[messageId]
        ?: ViewerPageState()
    if (page.loading || page.loaded && page.nextOffset == null && !page.failed) return
    val offset = if (page.loaded) page.nextOffset else null
    val epoch = warmup?.outgoingAccountEpoch()
    emit(Msg.ViewerPage(messageId, poll, page.copy(loading = true, failed = false)))
    work.launch {
        try {
            val result = if (poll) client.getPollVotersPage(chatId, messageId, offset)
            else client.getReactionUsersPage(chatId, messageId, offset)
            if (epoch != null && warmup?.outgoingAccountIsCurrent(epoch) != true) return@launch
            if (!snapshot().canView) return@launch
            when (result) {
                is Outcome.Err -> {
                    emit(Msg.ViewerPage(messageId, poll, page.copy(failed = true)))
                    handleError(result.telegramError, false)
                }

                is Outcome.Ok -> {
                    val consumed = page.consumedOffsets + (offset ?: "")
                    if (result.value.nextOffset != null && result.value.nextOffset in consumed) {
                        emit(Msg.ViewerPage(messageId, poll, page.copy(failed = true)))
                        return@launch
                    }
                    val old =
                        (if (poll) snapshot().pollVoters else snapshot().reactionUsers)[messageId].orEmpty()
                    val rows =
                        (if (page.loaded) old + result.value.viewers else result.value.viewers).distinctBy {
                            listOf(
                                it.peerId.value,
                                it.date,
                                it.emoticon,
                                it.documentId,
                                it.pollOptionHex
                            )
                        }
                    if (poll) emit(Msg.PollVoters(messageId, rows)) else emit(
                        Msg.ReactionUsers(
                            messageId, rows
                        )
                    )
                    emit(
                        Msg.ViewerPage(
                            messageId, poll, page.copy(
                                loaded = true,
                                loading = false,
                                failed = false,
                                nextOffset = result.value.nextOffset,
                                consumedOffsets = consumed
                            )
                        )
                    )
                }
            }
        } finally {
            val latest =
                (if (poll) snapshot().pollVoterPages else snapshot().reactionPages)[messageId]
            if (latest?.loading == true) emit(
                Msg.ViewerPage(
                    messageId, poll, latest.copy(loading = false)
                )
            )
        }
    }
}

internal fun DialogExecutor.loadReadReceipts(messageId: Int) {
    val current = snapshot()
    if (messageId <= 0) return
    val message = current.messages.firstOrNull { it.id.id == messageId } ?: return
    val now = System.currentTimeMillis() / 1000
    val config = current.readReceiptConfig
    val played = playedMediaKind(message.mediaKind)
    if (canShowMessageViewers(
            message = message,
            isGroup = current.isGroup,
            isChannel = current.isChannel,
            isSelf = current.isSelf,
            membersCount = current.membersCount,
            config = config,
            nowSeconds = now,
        )
    ) {
        if (current.messageViewers.containsKey(messageId) || !viewersInFlight.add(messageId)) return
        AppLog.api("dialog", "viewers request id=$messageId members=${current.membersCount}")
        emit(Msg.Viewers(messageId, MessageViewers.Loading))
        work.launch {
            ensureReadReceiptConfig()
            when (val result = client.getMessageViewers(
                chatId = snapshot().chatId,
                messageId = messageId,
                played = played,
                memberScanLimit = snapshot().readReceiptConfig.chatReadMarkSizeThreshold,
            )) {
                is Outcome.Ok -> {
                    if (result.value !is MessageViewers.Ready) {
                        AppLog.api("dialog", "receipts hidden state=${result.value} id=$messageId")
                    }
                    emit(Msg.Viewers(messageId, result.value))
                }

                is Outcome.Err -> {
                    AppLog.api("dialog", "getMessageViewers ${result.telegramError}")
                    emit(Msg.Viewers(messageId, MessageViewers.Unavailable))
                }
            }
            viewersInFlight.remove(messageId)
        }
        return
    }
    AppLog.api(
        "dialog",
        "receipts gated off id=$messageId group=${current.isGroup} channel=${current.isChannel} " + "members=${current.membersCount} out=${message.outgoing} mid=${message.id.id} " + "age=${now - message.date}",
    )
    if (canShowOutboxReadDate(
            message = message,
            isGroup = current.isGroup,
            isChannel = current.isChannel,
            isSelf = current.isSelf,
            readOutboxMaxId = current.readOutboxMaxId,
            config = config,
            nowSeconds = now,
        )
    ) {
        if (current.outboxReadStates.containsKey(messageId) || !readDatesInFlight.add(messageId)) return
        emit(Msg.OutboxRead(messageId, Loading))
        work.launch {
            ensureReadReceiptConfig()
            when (val result = client.getOutboxReadState(snapshot().chatId, messageId)) {
                is Outcome.Ok -> {
                    if (result.value !is OutboxReadState.Read) {
                        AppLog.api("dialog", "readDate state=${result.value} id=$messageId")
                    }
                    emit(Msg.OutboxRead(messageId, result.value))
                }

                is Outcome.Err -> {
                    AppLog.api("dialog", "getOutboxReadDate ${result.telegramError}")
                    emit(Msg.OutboxRead(messageId, OutboxReadState.Unavailable))
                }
            }
            readDatesInFlight.remove(messageId)
        }
    }
}

internal fun DialogExecutor.ensureReadReceiptConfig() {
    if (snapshot().readReceiptConfig.fromServer || !configInFlight.compareAndSet(
            false, true
        )
    ) return
    work.launch {
        when (val result = client.readReceiptConfig()) {
            is Outcome.Ok -> emit(Msg.ReadReceiptConfig(result.value))
            is Outcome.Err -> AppLog.api("dialog", "getReadReceiptConfig ${result.telegramError}")
        }
        configInFlight.set(false)
    }
}

internal fun DialogExecutor.markRead() {
    pendingUnreadAnchorId = null
    anchorToUnread = false
    val newest = newestLoadedIncomingId() ?: return
    acknowledgeRead(newest, immediate = true)
}

/**
 * [messageId] is the newest message the user can actually see on screen.
 * At the live edge the whole page is acknowledged: the newest row can be clipped by the
 * composer and a strict "newest visible" target would leave it unread forever.
 */

internal fun DialogExecutor.visibleRead(messageId: Int, atLiveEdge: Boolean) {
    if (anchorToUnread) return
    if (atLiveEdge) {
        // New-message updates and the viewport callback can arrive in the same main-loop turn.
        // Let pending updates reduce before selecting the bottom of the live window.
        work.launch {
            yield()
            val newestLoaded = newestLoadedIncomingId() ?: return@launch
            if (suppressLiveEdgeRead(
                    anchoringUnread = anchorToUnread,
                    pendingUnreadAnchorId = pendingUnreadAnchorId,
                    newestLoadedId = newestLoaded,
                    atLiveEdge = true,
                )
            ) return@launch
            pendingUnreadAnchorId = null
            val current = snapshot()
            val target = readReceiptTarget(
                newestVisibleId = newestLoaded,
                newestLoadedId = newestLoaded,
                readInboxMaxId = current.readInboxMaxId,
                hasNewer = current.hasNewer,
                searching = current.searchQuery.isNotBlank(),
            ) ?: return@launch
            acknowledgeRead(target, immediate = true)
        }
        return
    }

    val newestLoaded = newestLoadedIncomingId() ?: return
    if (pendingUnreadAnchorId != null && messageId <= pendingUnreadAnchorId!!) {
        pendingUnreadAnchorId = null
    }
    val current = snapshot()
    val target = readReceiptTarget(
        newestVisibleId = messageId,
        newestLoadedId = newestLoaded,
        readInboxMaxId = current.readInboxMaxId,
        hasNewer = current.hasNewer,
        searching = current.searchQuery.isNotBlank(),
    ) ?: return
    acknowledgeRead(target, immediate = false)
}

internal fun DialogExecutor.newestLoadedIncomingId(): Int? =
    snapshot().messages.filter { !it.outgoing }.maxOfOrNull { it.id.id }

internal fun DialogExecutor.acknowledgeRead(tillId: Int, immediate: Boolean) {
    if (!readReceiptsEnabled) return
    val current = snapshot()
    if (current.hasNewer || current.searchQuery.isNotBlank()) return
    if (tillId <= current.readInboxMaxId) return
    pendingReadMaxId = maxOf(pendingReadMaxId, tillId)
    if (readReceiptSending) return
    if (readReceiptJob?.isActive == true) {
        // A delayed receipt is already armed; the live edge sends without waiting it out.
        if (!immediate) return
        readReceiptJob?.cancel()
    }
    readReceiptJob = work.launch {
        if (!immediate) delay(READ_RECEIPT_DEBOUNCE_MS)
        readReceiptSending = true
        try {
            // Serialize receipts without cancelling an RPC when another message arrives.
            while (pendingReadMaxId > snapshot().readInboxMaxId) {
                val maxId = pendingReadMaxId
                AppLog.api("dialog", "read start chat=${chatId.value} max=$maxId")
                // Optimistic: reading must clear the badge at once. The receipt can queue for
                // seconds behind history work, and that delay made chats look unread.
                val previousRead = snapshot().readInboxMaxId
                val previousUnread = snapshot().unreadCount
                applyReadLocally(maxId)
                val result = if (ForumIo.usesDiscussionRead(threadTopMsgId)) {
                    client.readDiscussion(chatId, ForumIo.historyThreadId(threadTopMsgId), maxId)
                } else {
                    client.readHistory(chatId, maxId)
                }
                when (result) {
                    is Outcome.Ok -> {
                        AppLog.api("dialog", "read ok chat=${chatId.value} max=$maxId")
                    }

                    is Outcome.Err -> {
                        AppLog.api("dialog", "read failed chat=${chatId.value}")
                        // Put the optimistic state back so a failed receipt does not leave the
                        // chat looking read. Room keeps the newer boundary (its update is a
                        // guarded max), so the next successful receipt reconciles it.
                        emit(Msg.ReadInbox(previousRead))
                        emit(Msg.UnreadCount(previousUnread))
                        handleError(result.telegramError, false)
                        return@launch
                    }
                }
            }
        } finally {
            readReceiptSending = false
        }
    }
}

/** Optimistic receipt: store state first, then Room (the server echo can take seconds). */

internal suspend fun DialogExecutor.applyReadLocally(maxId: Int) {
    val confirmed = maxOf(snapshot().readInboxMaxId, maxId)
    emit(Msg.ReadInbox(confirmed))
    emit(Msg.UnreadCount(snapshot().messages.count {
        !it.outgoing && it.id.id > confirmed
    }))
    warmup?.markChatRead(chatId, confirmed)
}
