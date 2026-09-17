package org.monogram.feature.dialog.store

import kotlinx.coroutines.Job
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
import org.monogram.core.models.OutboxReadState.Loading
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
import org.monogram.feature.dialog.READ_RECEIPT_DEBOUNCE_MS
import org.monogram.feature.dialog.SEARCH_DEBOUNCE_MS
import org.monogram.feature.dialog.readReceiptTarget
import org.monogram.feature.dialog.suppressLiveEdgeRead
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

internal fun DialogExecutor.loadReactionUsers(messageId: Int) {
    if (snapshot().reactionUsers.containsKey(messageId)) return
    work.launch {
        when (val result = client.getReactionUsers(chatId, messageId)) {
            is Outcome.Ok -> emit(Msg.ReactionUsers(messageId, result.value))
            is Outcome.Err -> emit(Msg.ReactionUsers(messageId, emptyList()))
        }
    }
}

internal fun DialogExecutor.loadPollVoters(messageId: Int) {
    if (snapshot().pollVoters.containsKey(messageId)) return
    work.launch {
        when (val result = client.getPollVoters(chatId, messageId)) {
            is Outcome.Ok -> emit(Msg.PollVoters(messageId, result.value))
            is Outcome.Err -> emit(Msg.PollVoters(messageId, emptyList()))
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
            when (
                val result = client.getMessageViewers(
                    chatId = snapshot().chatId,
                    messageId = messageId,
                    played = played,
                    memberScanLimit = snapshot().readReceiptConfig.chatReadMarkSizeThreshold,
                )
            ) {
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
        "receipts gated off id=$messageId group=${current.isGroup} channel=${current.isChannel} " +
            "members=${current.membersCount} out=${message.outgoing} mid=${message.id.id} " +
            "age=${now - message.date}",
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
    if (snapshot().readReceiptConfig.fromServer || !configInFlight.compareAndSet(false, true)) return
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
    val newestLoaded = newestLoadedIncomingId() ?: return
    if (
        suppressLiveEdgeRead(
            anchoringUnread = anchorToUnread,
            pendingUnreadAnchorId = pendingUnreadAnchorId,
            newestLoadedId = newestLoaded,
            atLiveEdge = atLiveEdge,
        )
    ) {
        return
    }
    if (pendingUnreadAnchorId != null && (!atLiveEdge || messageId <= pendingUnreadAnchorId!!)) {
        pendingUnreadAnchorId = null
    }
    val current = snapshot()
    val target = readReceiptTarget(
        newestVisibleId = if (atLiveEdge) newestLoaded else messageId,
        newestLoadedId = newestLoaded,
        readInboxMaxId = current.readInboxMaxId,
        hasNewer = current.hasNewer,
        searching = current.searchQuery.isNotBlank(),
    ) ?: return
    acknowledgeRead(target, immediate = atLiveEdge || target >= newestLoaded)
}

internal fun DialogExecutor.newestLoadedIncomingId(): Int? =
    snapshot().messages.filter { !it.outgoing }.maxOfOrNull { it.id.id }

internal fun DialogExecutor.acknowledgeRead(tillId: Int, immediate: Boolean) {
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
