package org.monogram.presentation.features.chats.conversation.logic

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.monogram.core.perf.ChatOpenPerfBridge
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ReadUpdate
import org.monogram.presentation.features.chats.conversation.AutoDownloadSuppression
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ChatConversationLog
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ChatViewportPhase
import org.monogram.presentation.features.chats.conversation.ConversationLoadSession
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent
import org.monogram.presentation.features.chats.conversation.ScrollAlign
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds


private const val PAGE_SIZE = 50
private const val MAX_DOWNLOAD_RETRIES = 3
private const val SENDER_REFRESH_TTL_MS = 60_000L
private fun isUsableAvatarPath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return when {
        path.startsWith("http", ignoreCase = true) -> true
        path.startsWith("content:", ignoreCase = true) -> true
        path.startsWith("file:", ignoreCase = true) -> true
        else -> File(path).exists()
    }
}

private fun firstUsableAvatarPath(vararg candidates: String?): String? {
    return candidates.firstOrNull { isUsableAvatarPath(it) }
        ?: candidates.firstOrNull { !it.isNullOrBlank() }
}

private fun mergeSenderVisuals(previous: MessageModel, incoming: MessageModel): MessageModel {
    if (previous.senderId != incoming.senderId) return incoming

    val mergedAvatar = firstUsableAvatarPath(
        incoming.senderAvatar,
        incoming.senderPersonalAvatar,
        previous.senderAvatar,
        previous.senderPersonalAvatar
    )
    val mergedPersonalAvatar = firstUsableAvatarPath(
        incoming.senderPersonalAvatar,
        incoming.senderAvatar,
        previous.senderPersonalAvatar,
        previous.senderAvatar
    )

    return incoming.copy(
        senderName = incoming.senderName.ifBlank { previous.senderName },
        senderAvatar = mergedAvatar,
        senderPersonalAvatar = mergedPersonalAvatar,
        senderCustomTitle = incoming.senderCustomTitle ?: previous.senderCustomTitle,
        senderStatusEmojiPath = incoming.senderStatusEmojiPath ?: previous.senderStatusEmojiPath,
        reactions = incoming.reactions.ifEmpty { previous.reactions }
    )
}

private fun MessageModel.needsSenderRefresh(): Boolean {
    if (senderId <= 0L) return false
    val hasPlaceholderName = senderName.isBlank()
    val hasNoAvatar = senderAvatar.isNullOrBlank() && senderPersonalAvatar.isNullOrBlank()
    return hasPlaceholderName || hasNoAvatar
}

internal fun DefaultChatComponent.requestSenderRefreshIfNeeded(message: MessageModel) {
    if (!message.needsSenderRefresh()) return
    requestSenderRefresh(message.senderId)
}

internal fun DefaultChatComponent.requestSenderRefresh(senderId: Long) {
    if (senderId <= 0L) return
    val now = System.currentTimeMillis()
    val lastRequestedAt = senderRefreshRequestedAtMs[senderId]
    if (lastRequestedAt != null && now - lastRequestedAt < SENDER_REFRESH_TTL_MS) return
    if (!pendingSenderRefreshes.add(senderId)) return
    senderRefreshRequestedAtMs[senderId] = now

    scope.launch {
        try {
            repositoryMessage.invalidateSenderCache(senderId)
            val user = userRepository.getUser(senderId) ?: return@launch
            refreshMessagesForSender(senderId, user)
        } finally {
            pendingSenderRefreshes.remove(senderId)
        }
    }
}

private fun reactionsSemanticEqual(
    current: List<MessageReactionModel>,
    incoming: List<MessageReactionModel>
): Boolean {
    if (current.size != incoming.size) return false

    val currentByReaction = current.associateBy { it.emoji to it.customEmojiId }
    if (currentByReaction.size != current.size) return false

    return incoming.all { reaction ->
        val previous = currentByReaction[reaction.emoji to reaction.customEmojiId] ?: return@all false
        previous.count == reaction.count &&
                previous.isChosen == reaction.isChosen &&
                previous.customEmojiPath == reaction.customEmojiPath &&
                previous.recentSenders == reaction.recentSenders
    }
}

private fun DefaultChatComponent.resolveRemappedMessageId(messageId: Long): Long {
    var current = messageId
    repeat(4) {
        val mapped = remappedMessageIds[current] ?: return current
        if (mapped == current) return current
        current = mapped
    }
    return current
}

private suspend fun DefaultChatComponent.updateMessagesUnsafe(
    newMessages: List<MessageModel>,
    replace: Boolean = false
) {
    val currentState = _state.value
    val adBlockEnabled = appPreferences.isAdBlockEnabled.value
    val keywords = appPreferences.adBlockKeywords.value
    val whitelistedChannels = appPreferences.adBlockWhitelistedChannels.value
    val isChannel = currentState.isChannel
    val isWhitelisted = whitelistedChannels.contains(chatId)

    val filteredNewMessages = if (adBlockEnabled && isChannel && !isWhitelisted) {
        withContext(Dispatchers.Default) {
            newMessages.filterNot { message ->
                val text = when (val content = message.content) {
                    is MessageContent.Text -> content.text
                    is MessageContent.Photo -> content.caption
                    is MessageContent.Video -> content.caption
                    is MessageContent.Document -> content.caption
                    is MessageContent.Gif -> content.caption
                    else -> ""
                }
                keywords.any { text.contains(it, ignoreCase = true) }
            }
        }
    } else {
        newMessages
    }

    _state.update { state ->
        if (filteredNewMessages.isEmpty()) {
            return@update if (replace && state.messages.any { it.sendingState is MessageSendingState.Pending }) {
                state.copy(messages = state.messages.filter { it.sendingState is MessageSendingState.Pending })
            } else {
                state
            }
        }

        val currentList = if (replace) {
            state.messages.filter { it.sendingState is MessageSendingState.Pending }
        } else {
            state.messages
        }
        val existingReactionsById = if (replace) {
            state.messages
                .filter { it.reactions.isNotEmpty() }
                .associate { it.id to it.reactions }
        } else {
            emptyMap()
        }
        val previousMessagesById = if (replace) {
            state.messages.associateBy { it.id }
        } else {
            emptyMap()
        }

        val isComments = state.rootMessage != null

        val messageMap = LinkedHashMap<Long, MessageModel>(currentList.size + filteredNewMessages.size)
        currentList.forEach { messageMap[it.id] = it }

        var hasChanges = replace
        filteredNewMessages.forEach { msg ->
            val previous = messageMap[msg.id] ?: previousMessagesById[msg.id]
            val mergedMessage = if (previous != null) mergeSenderVisuals(previous, msg) else msg
            val restoredMessage = if (mergedMessage.reactions.isEmpty()) {
                val previousReactions = existingReactionsById[msg.id]
                if (!previousReactions.isNullOrEmpty()) {
                    mergedMessage.copy(reactions = previousReactions)
                } else {
                    mergedMessage
                }
            } else {
                mergedMessage
            }
            val contentSafeMessage = state.preservePendingEditedContent(
                incomingMessage = restoredMessage,
                previousMessage = previous
            )
            val old = messageMap.put(msg.id, contentSafeMessage)
            if (old != contentSafeMessage) {
                hasChanges = true
            }
        }

        if (!hasChanges) {
            return@update state
        }

        val mergedMessages = messageMap.values.let {
            val sortedMessages = if (isComments) {
                it.sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
            } else {
                it.sortedWith(compareByDescending<MessageModel> { it.date }.thenByDescending { it.id })
            }
            pruneDeliveredPendingDuplicates(sortedMessages)
        }

        if (mergedMessages == state.messages) state else state.copy(messages = mergedMessages)
    }
}

internal suspend fun DefaultChatComponent.updateMessages(newMessages: List<MessageModel>, replace: Boolean = false) {
    messageMutex.withLock {
        updateMessagesUnsafe(newMessages, replace)
    }
}

private fun pruneDeliveredPendingDuplicates(messages: List<MessageModel>): List<MessageModel> {
    if (messages.none { it.sendingState is MessageSendingState.Pending }) return messages

    return messages.filterNot { candidate ->
        if (candidate.sendingState !is MessageSendingState.Pending || !candidate.isOutgoing) {
            return@filterNot false
        }

        messages.any { confirmed ->
            confirmed.id != candidate.id &&
                    confirmed.isOutgoing &&
                    confirmed.sendingState !is MessageSendingState.Pending &&
                    confirmed.chatId == candidate.chatId &&
                    confirmed.threadId == candidate.threadId &&
                    confirmed.senderId == candidate.senderId &&
                    abs(confirmed.date - candidate.date) <= 300 &&
                    confirmed.deliverySignature() == candidate.deliverySignature()
        }
    }
}

private fun MessageModel.deliverySignature(): String? {
    return when (val c = content) {
        is MessageContent.Text -> "text:${c.text.trim()}"
        is MessageContent.Photo -> "photo:${c.caption.trim()}"
        is MessageContent.Video -> "video:${c.caption.trim()}"
        is MessageContent.Gif -> "gif:${c.caption.trim()}"
        is MessageContent.Document -> "document:${c.caption.trim()}"
        is MessageContent.Audio -> "audio:${c.caption.trim()}"
        else -> null
    }
}

private fun MessageModel.hasUnresolvableCachedMedia(): Boolean {
    return when (val c = content) {
        is MessageContent.Photo -> c.path.isNullOrBlank() && c.thumbnailPath.isNullOrBlank() &&
                c.fileId == 0 && c.originalFileId == 0

        is MessageContent.Video -> c.path.isNullOrBlank() && c.thumbnailPath.isNullOrBlank() && c.fileId == 0
        is MessageContent.Voice -> c.path.isNullOrBlank() && c.fileId == 0
        is MessageContent.VideoNote -> c.path.isNullOrBlank() && c.thumbnail.isNullOrBlank() && c.fileId == 0
        is MessageContent.Sticker -> c.path.isNullOrBlank() && c.fileId == 0
        is MessageContent.Document -> c.path.isNullOrBlank() && c.fileId == 0
        is MessageContent.Audio -> c.path.isNullOrBlank() && c.fileId == 0
        is MessageContent.Gif -> c.path.isNullOrBlank() && c.fileId == 0
        else -> false
    }
}

internal fun DefaultChatComponent.loadMessages(
    force: Boolean = false,
    loadSource: String = "manual"
) {
    val state = _state.value
    // A forced load must be able to pre-empt one already in flight. handleTopicClick() clears
    // `messages` and sets `viewportPhase = Initializing` before calling this, so dropping the
    // call here left the screen permanently blank with nothing to re-trigger it: tapping a topic
    // while the forum root was still loading (~2s) was silently ignored.
    // cancelAllLoadingJobs() below tears down the in-flight load, so pre-empting is safe.
    if (state.isLoading && !force) return
    if (!force && state.messages.size >= PAGE_SIZE && state.currentTopicId == null) return

    cancelAllLoadingJobs()
    messageLoadingJob = scope.launch {
        val openStartedAt = System.currentTimeMillis()
        ChatConversationLog.logViewportState(
            event = "load_messages_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "source=$loadSource force=$force"
        )
        _state.update {
            it.copy(
                isLoading = true,
                isOldestLoaded = false,
                isLatestLoaded = false,
                pendingScrollCommand = null,
                viewportPhase = ChatViewportPhase.Initializing
            )
        }
        ChatConversationLog.logViewportState(
            event = "load_messages_started",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "source=$loadSource force=$force"
        )

        try {
            val currentState = _state.value
            val threadId = currentState.effectiveThreadId()
            val targetChatId = currentState.effectiveThreadChatId(chatId)
            val isComments = currentState.rootMessage != null
            val savedViewport = cacheProvider.getChatViewport(chatId, threadId)
            _state.update { it.copy(lastSavedViewport = savedViewport) }
            val sessionSnapshot = ChatOpenPerfBridge.startSession(
                chatId = chatId,
                threadId = threadId,
                source = loadSource,
                target = "resolving"
            )
            activeLoadSession = ConversationLoadSession(
                sessionId = sessionSnapshot.sessionId,
                source = loadSource,
                target = "resolving"
            )

            val unreadSeparatorCount = currentState.unreadSeparatorCount
            val unreadSeparatorLastReadInboxMessageId =
                currentState.unreadSeparatorLastReadInboxMessageId
            val firstUnreadId = unreadSeparatorLastReadInboxMessageId
                .takeIf { unreadSeparatorCount > 0 }
                ?.let { lastRead ->
                    repositoryMessage.getMessagesNewer(targetChatId, lastRead, 1, threadId)
                        .firstOrNull()
                        ?.id
                        ?: lastRead.takeIf { it > 0L }
                }

            val target = resolveInitialChatScrollTarget(
                    explicitMessageId = currentState.scrollToMessageId,
                    savedViewport = savedViewport,
                    firstUnreadMessageId = firstUnreadId,
                    unreadCount = unreadSeparatorCount,
                    backfillUnreadThreshold = PAGE_SIZE,
                    isComments = isComments
                )
            ChatConversationLog.logViewportState(
                event = "load_messages_target_resolved",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "source=$loadSource target=${target.perfTargetName()} targetChatId=$targetChatId targetAnchor=${target.anchorMessageId ?: 0L} savedAnchor=${savedViewport?.anchorMessageId ?: 0L} firstUnreadId=${firstUnreadId ?: 0L}"
            )
            ChatOpenPerfBridge.updateTarget(chatId, threadId, target.perfTargetName())
            activeLoadSession = ConversationLoadSession(
                sessionId = sessionSnapshot.sessionId,
                source = loadSource,
                target = target.perfTargetName()
            )
            ChatConversationLog.logPerf(
                component = this@loadMessages,
                phase = "chat_open_total_start",
                source = loadSource,
                target = target.perfTargetName(),
                anchorId = target.anchorMessageId ?: savedViewport?.anchorMessageId
            )

            when (target) {
                is InitialChatScrollTarget.AroundMessage -> {
                    loadAroundMessage(
                        chatId = targetChatId,
                        messageId = target.messageId,
                        threadId = threadId,
                        shouldHighlight = target.highlight,
                        scrollCommand = target.command,
                        backfillNewerAfterInitialLoad = target.backfillNewerAfterInitialLoad
                    )
                }

                is InitialChatScrollTarget.Comments -> {
                    if (threadId == null) {
                        loadBottomMessages(
                            targetChatId = targetChatId,
                            threadId = threadId,
                            scrollCommand = ChatScrollCommand.ScrollToBottom(animated = false)
                        )
                        return@launch
                    }
                    loadComments(
                        targetChatId = targetChatId,
                        threadId = threadId,
                        scrollCommand = target.command
                    )
                }

                is InitialChatScrollTarget.Bottom -> {
                    loadBottomMessages(
                        targetChatId = targetChatId,
                        threadId = threadId,
                        scrollCommand = target.command
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatConversationLog.logViewportState(
                event = "load_messages_failed",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "source=$loadSource error=${e.javaClass.simpleName}"
            )
            Log.e("DefaultChatComponent", "Failed to load messages", e)
        } finally {
            ChatConversationLog.logPerf(
                component = this@loadMessages,
                phase = "chat_open_total_end",
                durationMs = System.currentTimeMillis() - openStartedAt
            )
            // Only clear the flag if this job finished on its own. cancelAllLoadingJobs() cancels
            // without joining, so a pre-empted job's finally runs *after* the replacement job has
            // already set isLoading = true -- clearing it here would clobber the live load.
            if (isActive) {
                _state.update { it.copy(isLoading = false) }
            }
            ChatConversationLog.logViewportState(
                event = "load_messages_finished",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "source=$loadSource"
            )
        }
    }
}

internal suspend fun DefaultChatComponent.loadComments(
    targetChatId: Long = activeThreadChatId(),
    threadId: Long,
    scrollCommand: ChatScrollCommand? = ChatScrollCommand.ScrollToStart(animated = false)
) {
    lastLoadedOlderId = 0L
    lastLoadedNewerId = 0L
    val olderPage = repositoryMessage.getMessagesOlder(targetChatId, 0L, PAGE_SIZE, threadId)
    val messages = olderPage.messages
    val reachedOldest = olderPage.reachedOldest

    _state.update {
        it.copy(
            isAtBottom = when (scrollCommand) {
                is ChatScrollCommand.ScrollToBottom -> true
                is ChatScrollCommand.RestoreViewport -> scrollCommand.atBottom
                else -> false
            },
            isLatestLoaded = true,
            isOldestLoaded = reachedOldest,
            scrollToMessageId = null,
            highlightRequest = null
        )
    }
    updateMessages(messages, replace = true)
    refreshCachedSenderProfiles(messages)
    if (scrollCommand != null) {
        _state.update {
            it.copy(
                pendingScrollCommand = scrollCommand,
                viewportPhase = ChatViewportPhase.Restoring
            )
        }
        ChatConversationLog.logViewportState(
            event = "load_comments_restore",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messages=${messages.size} reachedOldest=$reachedOldest command=${scrollCommand.javaClass.simpleName}"
        )
    }
}

private suspend fun DefaultChatComponent.loadBottomMessages(
    targetChatId: Long = activeThreadChatId(),
    threadId: Long?,
    scrollCommand: ChatScrollCommand? = null
) {
    lastLoadedOlderId = 0L
    lastLoadedNewerId = 0L

    var hasCachedPreview = false
    val cachedMessages = repositoryMessage.getCachedMessages(targetChatId, PAGE_SIZE, threadId)
    if (cachedMessages.isNotEmpty()) {
        hasCachedPreview = true
        _state.update {
            it.copy(
                isAtBottom = true,
                isLatestLoaded = false,
                isOldestLoaded = false,
                scrollToMessageId = null,
                highlightRequest = null
            )
        }
        updateMessages(cachedMessages, replace = true)
        refreshCachedSenderProfiles(cachedMessages)
        ChatConversationLog.logViewportState(
            event = "load_bottom_cached_preview",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetChatId=$targetChatId cachedMessages=${cachedMessages.size}"
        )
    }

    val olderPage = repositoryMessage.getMessagesOlder(targetChatId, 0, PAGE_SIZE, threadId)
    val messages = olderPage.messages
    val isRemoteSameAsCachedPreview = hasCachedPreview && cachedMessages.isNotEmpty() &&
            messages.size == cachedMessages.size &&
            messages.zip(cachedMessages).all { (remote, cached) ->
                remote.id == cached.id && !cached.hasUnresolvableCachedMedia()
            }

    val isOldestLoaded = if (isRemoteSameAsCachedPreview) {
        false
    } else {
        olderPage.reachedOldest
    }

    if (!isRemoteSameAsCachedPreview) {
        _state.update {
            it.copy(
                isAtBottom = true,
                isLatestLoaded = true,
                isOldestLoaded = isOldestLoaded,
                scrollToMessageId = null,
                highlightRequest = null
            )
        }
        val shouldReplaceCachedPreview = !hasCachedPreview || messages.isNotEmpty()
        updateMessages(messages, replace = shouldReplaceCachedPreview)
        refreshCachedSenderProfiles(messages)
        ChatConversationLog.logViewportState(
            event = "load_bottom_remote_applied",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetChatId=$targetChatId remoteMessages=${messages.size} replace=${shouldReplaceCachedPreview} reachedOldest=${olderPage.reachedOldest}"
        )
    } else {
        _state.update {
            it.copy(
                isAtBottom = true,
                isLatestLoaded = true,
                isOldestLoaded = false,
                scrollToMessageId = null,
                highlightRequest = null
            )
        }
        ChatConversationLog.logViewportState(
            event = "load_bottom_remote_same_as_cache",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetChatId=$targetChatId remoteMessages=${messages.size}"
        )
    }
    if (scrollCommand != null) {
        _state.update {
            it.copy(
                pendingScrollCommand = scrollCommand,
                viewportPhase = ChatViewportPhase.Restoring
            )
        }
        ChatConversationLog.logViewportState(
            event = "load_bottom_restore",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "command=${scrollCommand.javaClass.simpleName}"
        )
    }
}

private suspend fun DefaultChatComponent.loadAroundMessage(
    chatId: Long = activeThreadChatId(),
    messageId: Long,
    threadId: Long?,
    shouldHighlight: Boolean = true,
    backfillNewerAfterInitialLoad: Boolean = false,
    scrollCommand: ChatScrollCommand? = ChatScrollCommand.JumpToMessage(
        messageId = messageId,
        highlight = shouldHighlight,
        align = ScrollAlign.Center,
        animated = true
    )
) {
    lastLoadedOlderId = 0L
    lastLoadedNewerId = 0L
    var hasTargetPreview = false
    val cachedMessages =
        repositoryMessage.getCachedMessagesAround(chatId, messageId, PAGE_SIZE, threadId)
    if (cachedMessages.any { it.id == messageId }) {
        hasTargetPreview = true
        _state.update {
            it.copy(
                isAtBottom = false,
                isLatestLoaded = false,
                isOldestLoaded = false,
                scrollToMessageId = null,
                highlightRequest = null
            )
        }
        updateMessages(cachedMessages, replace = true)
        refreshCachedSenderProfiles(cachedMessages)
        ChatConversationLog.logViewportState(
            event = "load_around_cached_preview",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetMessageId=$messageId cachedMessages=${cachedMessages.size}"
        )
        if (scrollCommand != null) {
            _state.update {
                it.copy(
                    pendingScrollCommand = scrollCommand,
                    viewportPhase = ChatViewportPhase.Restoring
                )
            }
            ChatConversationLog.logViewportState(
                event = "load_around_cached_restore",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "command=${scrollCommand.javaClass.simpleName} targetMessageId=$messageId"
            )
        }
    }

    val messages = repositoryMessage.getMessagesAround(chatId, messageId, PAGE_SIZE, threadId)
    if (messages.isNotEmpty()) {
        _state.update {
            it.copy(
                isAtBottom = false,
                isLatestLoaded = false,
                isOldestLoaded = false,
                scrollToMessageId = null,
                highlightRequest = null
            )
        }
        updateMessages(messages, replace = !hasTargetPreview)
        refreshCachedSenderProfiles(messages)
        ChatConversationLog.logViewportState(
            event = "load_around_remote_applied",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetMessageId=$messageId remoteMessages=${messages.size} replace=${!hasTargetPreview}"
        )
        if (!hasTargetPreview && scrollCommand != null) {
            _state.update {
                it.copy(
                    pendingScrollCommand = scrollCommand,
                    viewportPhase = ChatViewportPhase.Restoring
                )
            }
            ChatConversationLog.logViewportState(
                event = "load_around_remote_restore",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "command=${scrollCommand.javaClass.simpleName} targetMessageId=$messageId"
            )
        }
        if (backfillNewerAfterInitialLoad) {
            ChatConversationLog.logViewportState(
                event = "load_around_schedule_backfill",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "targetMessageId=$messageId maxPages=3"
            )
            scheduleUnreadBackfill(maxPages = 3)
        }
    } else if (hasTargetPreview) {
        if (backfillNewerAfterInitialLoad) {
            ChatConversationLog.logViewportState(
                event = "load_around_schedule_backfill_from_cache_only",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "targetMessageId=$messageId maxPages=3"
            )
            scheduleUnreadBackfill(maxPages = 3)
        }
    } else {
        ChatConversationLog.logViewportState(
            event = "load_around_fallback",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetMessageId=$messageId hasTargetPreview=$hasTargetPreview rootMessage=${_state.value.rootMessage?.id ?: 0L}"
        )
        if (_state.value.rootMessage != null && threadId != null) {
            loadComments(
                targetChatId = chatId,
                threadId = threadId,
                scrollCommand = ChatScrollCommand.ScrollToStart(animated = false)
            )
        } else {
            loadBottomMessages(
                targetChatId = chatId,
                threadId = threadId,
                scrollCommand = ChatScrollCommand.ScrollToBottom(animated = false)
            )
        }
    }
}

private suspend fun DefaultChatComponent.backfillNewerMessages(maxPages: Int) {
    repeat(maxPages) {
        if (_state.value.isLatestLoaded) return
        val reachedLatest = loadNewerMessagesPage()
        if (reachedLatest) return
    }
}

internal fun DefaultChatComponent.scheduleUnreadBackfill(maxPages: Int) {
    unreadBackfillJob?.cancel()
    ChatConversationLog.logViewportState(
        event = "schedule_unread_backfill",
        state = _state.value,
        componentInstanceId = componentInstanceId,
        extra = "maxPages=$maxPages"
    )
    unreadBackfillJob = scope.launch {
        while (true) {
            val state = _state.value
            if (state.viewportPhase == ChatViewportPhase.Settled) break
            if (!isActive || state.isAtBottom || state.isLoading || state.isLoadingOlder || state.isLoadingNewer) {
                ChatConversationLog.logViewportState(
                    event = "schedule_unread_backfill_abort",
                    state = state,
                    componentInstanceId = componentInstanceId,
                    extra = "maxPages=$maxPages"
                )
                return@launch
            }
            delay(50.milliseconds)
        }
        ChatConversationLog.logViewportState(
            event = "schedule_unread_backfill_run",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "maxPages=$maxPages unreadSeparatorCount=${_state.value.unreadSeparatorCount}"
        )
        if (_state.value.unreadSeparatorCount > PAGE_SIZE) {
            backfillNewerMessages(maxPages = maxPages)
        }
    }
}

internal fun DefaultChatComponent.requestMessageHighlight(messageId: Long) {
    _state.update { currentState ->
        val nextToken = currentState.highlightRequestToken + 1L
        currentState.copy(
            highlightRequest = org.monogram.presentation.features.chats.conversation.MessageHighlightRequest(
                messageId = messageId,
                token = nextToken
            ),
            highlightRequestToken = nextToken
        )
    }
}

internal fun ChatComponent.State.enqueueRuntimeScrollToBottom(
    animated: Boolean = true
): ChatComponent.State {
    return copy(
        isAtBottom = true,
        pendingScrollCommand = ChatScrollCommand.ScrollToBottom(animated = animated)
    )
}

private fun DefaultChatComponent.queueJumpToLoadedMessage(
    messageId: Long,
    highlight: Boolean,
    align: ScrollAlign = ScrollAlign.Center,
    animated: Boolean = true
) {
    _state.update {
        it.copy(
            isAtBottom = false,
            viewportPhase = ChatViewportPhase.Restoring,
            pendingScrollCommand = ChatScrollCommand.JumpToMessage(
                messageId = messageId,
                highlight = highlight,
                align = align,
                animated = animated
            ),
            highlightRequest = null
        )
    }
}

internal fun DefaultChatComponent.loadMoreMessages() {
    val state = _state.value
    val forceLoad = state.isOldestLoaded && state.messages.size < 10
    val isComments = state.rootMessage != null
    val visibleAnchorId = if (isComments) {
        state.messages.firstOrNull { it.id > 0 }?.id ?: 0L
    } else {
        state.messages.lastOrNull { it.id > 0 }?.id ?: 0L
    }
    val requestedAnchorId = listOf(visibleAnchorId, lastLoadedOlderId)
        .filter { it > 0L }
        .minOrNull() ?: 0L

    if (requestedAnchorId != 0L && requestedAnchorId == inFlightOlderAnchorId) return
    if (loadMoreJob?.isActive == true || state.isLoadingOlder || (state.isOldestLoaded && !forceLoad)) return

    if (requestedAnchorId != 0L) {
        inFlightOlderAnchorId = requestedAnchorId
    }

    loadMoreJob = scope.launch {
        _state.update { it.copy(isLoadingOlder = true) }
        try {
            val currentState = _state.value
            val isComments = currentState.rootMessage != null
            val threadId = currentState.effectiveThreadId()
            val targetChatId = currentState.effectiveThreadChatId(chatId)

            val visibleAnchorId = if (isComments) {
                currentState.messages.firstOrNull { it.id > 0 }?.id ?: 0L
            } else {
                currentState.messages.lastOrNull { it.id > 0 }?.id ?: 0L
            }

            val anchorId = listOf(visibleAnchorId, lastLoadedOlderId)
                .filter { it > 0L }
                .minOrNull() ?: 0L

            inFlightOlderAnchorId = anchorId

            if (anchorId == 0L) {
                _state.update { it.copy(isOldestLoaded = true) }
                return@launch
            }

            var currentAnchorId = anchorId
            var isOldestLoaded = false
            var attempts = 0

            while (!isOldestLoaded && attempts < 5) {
                attempts++

                val beforeSize = _state.value.messages.size
                val olderPage = repositoryMessage.getMessagesOlder(
                    targetChatId,
                    currentAnchorId,
                    PAGE_SIZE,
                    threadId
                )
                val olderMessages = olderPage.messages

                val nextOlderAnchorId = olderMessages
                    .asSequence()
                    .map { it.id }
                    .filter { it in 1 until currentAnchorId }
                    .minOrNull() ?: currentAnchorId

                val hasOlderProgress = nextOlderAnchorId < currentAnchorId

                if (olderMessages.isNotEmpty()) {
                    updateMessages(olderMessages)
                    refreshCachedSenderProfiles(olderMessages)
                }

                val afterSize = _state.value.messages.size
                val listGrew = afterSize > beforeSize

                isOldestLoaded = olderPage.reachedOldest || (olderPage.isRemote && !hasOlderProgress)

                if (hasOlderProgress) {
                    lastLoadedOlderId = nextOlderAnchorId
                    currentAnchorId = nextOlderAnchorId
                }

                if (!olderPage.isRemote && olderMessages.isEmpty()) {
                    break
                }

                if (isOldestLoaded || listGrew) break
            }

            _state.update { it.copy(isOldestLoaded = isOldestLoaded) }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load more messages", e)
            lastLoadedOlderId = 0L
        } finally {
            inFlightOlderAnchorId = 0L
            _state.update { it.copy(isLoadingOlder = false) }
        }
    }
}

internal fun DefaultChatComponent.loadNewerMessages() {
    val state = _state.value
    val requestedAnchorId = if (state.rootMessage != null) {
        state.messages.lastOrNull { it.id > 0 }?.id ?: 0L
    } else {
        state.messages.firstOrNull { it.id > 0 }?.id ?: 0L
    }

    if (requestedAnchorId != 0L && requestedAnchorId == inFlightNewerAnchorId) return
    if (loadNewerJob?.isActive == true || state.isLoadingNewer || state.isLatestLoaded) return

    if (requestedAnchorId != 0L) {
        inFlightNewerAnchorId = requestedAnchorId
    }

    loadNewerJob = scope.launch {
        _state.update { it.copy(isLoadingNewer = true) }
        try {
            loadNewerMessagesPage()
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load newer messages", e)
            lastLoadedNewerId = 0L
        } finally {
            inFlightNewerAnchorId = 0L
            _state.update { it.copy(isLoadingNewer = false) }
        }
    }
}

private suspend fun DefaultChatComponent.loadNewerMessagesPage(): Boolean {
    val currentState = _state.value
    val currentMessages = currentState.messages
    val isComments = currentState.rootMessage != null
    val threadId = currentState.effectiveThreadId()
    val targetChatId = currentState.effectiveThreadChatId(chatId)

    val anchorId = if (isComments) {
        currentMessages.lastOrNull { it.id > 0 }?.id ?: return false
    } else {
        currentMessages.firstOrNull { it.id > 0 }?.id ?: return false
    }

    if (anchorId != 0L && anchorId == lastLoadedNewerId) {
        _state.update { it.copy(isLatestLoaded = true) }
        return true
    }

    val newerMessages =
        repositoryMessage.getMessagesNewer(targetChatId, anchorId, PAGE_SIZE, threadId)
    val isLatestLoaded =
        newerMessages.size < PAGE_SIZE ||
                (newerMessages.isNotEmpty() && newerMessages.all { msg -> currentMessages.any { it.id == msg.id } })

    if (newerMessages.isNotEmpty()) {
        updateMessages(newerMessages)
        refreshCachedSenderProfiles(newerMessages)
        lastLoadedNewerId = anchorId
    }

    _state.update { it.copy(isLatestLoaded = isLatestLoaded) }
    return isLatestLoaded
}

internal fun DefaultChatComponent.scrollToMessageInternal(messageId: Long) {
    val currentState = _state.value
    ChatConversationLog.logViewportState(
        event = "scroll_to_message_requested",
        state = currentState,
        componentInstanceId = componentInstanceId,
        extra = "messageId=$messageId alreadyLoaded=${currentState.messages.any { it.id == messageId }}"
    )
    if (currentState.messages.any { it.id == messageId }) {
        queueJumpToLoadedMessage(
            messageId = messageId,
            highlight = true,
            align = ScrollAlign.Center,
            animated = true
        )
        return
    }

    cancelAllLoadingJobs()
    messageLoadingJob = scope.launch {
        ChatConversationLog.logViewportState(
            event = "scroll_to_message_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messageId=$messageId"
        )
        _state.update {
            it.copy(
                isLoading = true,
                isOldestLoaded = false,
                isLatestLoaded = false,
                pendingScrollCommand = null,
                viewportPhase = ChatViewportPhase.Initializing,
                highlightRequest = null
            )
        }
        ChatConversationLog.logViewportState(
            event = "scroll_to_message_reload_start",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messageId=$messageId"
        )
        try {
            loadAroundMessage(
                messageId = messageId,
                chatId = activeThreadChatId(),
                threadId = activeThreadId(),
                shouldHighlight = true,
                scrollCommand = ChatScrollCommand.JumpToMessage(
                    messageId = messageId,
                    highlight = true,
                    align = ScrollAlign.Center,
                    animated = true
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatConversationLog.logViewportState(
                event = "scroll_to_message_reload_failed",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "messageId=$messageId error=${e.javaClass.simpleName}"
            )
            Log.e("DefaultChatComponent", "Failed to scroll to message", e)
        } finally {
            _state.update { it.copy(isLoading = false) }
            ChatConversationLog.logViewportState(
                event = "scroll_to_message_reload_finish",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "messageId=$messageId"
            )
        }
    }
}

internal fun DefaultChatComponent.scrollToBottomInternal() {
    val currentState = _state.value
    val threadId = currentState.effectiveThreadId()
    val isComments = currentState.rootMessage != null
    val targetChatId = currentState.effectiveThreadChatId(chatId)
    ChatConversationLog.logViewportState(
        event = "scroll_to_bottom_requested",
        state = currentState,
        componentInstanceId = componentInstanceId,
        extra = "isComments=$isComments"
    )
    if (currentState.messages.isNotEmpty() && currentState.isLatestLoaded) {
        _state.update { it.enqueueRuntimeScrollToBottom(animated = true) }
        ChatConversationLog.logViewportState(
            event = "scroll_to_bottom_runtime_enqueued",
            state = _state.value,
            componentInstanceId = componentInstanceId
        )
        return
    }
    if (currentState.messages.isNotEmpty() && !currentState.isLatestLoaded) {
        _state.update { it.enqueueRuntimeScrollToBottom(animated = true) }
        ChatConversationLog.logViewportState(
            event = "scroll_to_bottom_partial_runtime_enqueued",
            state = _state.value,
            componentInstanceId = componentInstanceId
        )
        if (loadNewerJob?.isActive == true || currentState.isLoadingNewer) return
        loadNewerJob = scope.launch {
            _state.update { it.copy(isLoadingNewer = true) }
            ChatConversationLog.logViewportState(
                event = "scroll_to_bottom_load_newer_start",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
            try {
                var reachedLatest = false
                repeat(20) {
                    if (reachedLatest || _state.value.isLatestLoaded) {
                        reachedLatest = true
                        return@repeat
                    }
                    reachedLatest = loadNewerMessagesPage()
                }
                if (reachedLatest || _state.value.isLatestLoaded) {
                    _state.update { it.enqueueRuntimeScrollToBottom(animated = true) }
                    ChatConversationLog.logViewportState(
                        event = "scroll_to_bottom_load_newer_enqueued",
                        state = _state.value,
                        componentInstanceId = componentInstanceId
                    )
                }
            } catch (e: Exception) {
                ChatConversationLog.logViewportState(
                    event = "scroll_to_bottom_load_newer_failed",
                    state = _state.value,
                    componentInstanceId = componentInstanceId,
                    extra = "error=${e.javaClass.simpleName}"
                )
                Log.e(
                    "DefaultChatComponent",
                    "Failed to load newer messages before scroll to bottom",
                    e
                )
                lastLoadedNewerId = 0L
            } finally {
                inFlightNewerAnchorId = 0L
                _state.update { it.copy(isLoadingNewer = false) }
                ChatConversationLog.logViewportState(
                    event = "scroll_to_bottom_load_newer_finish",
                    state = _state.value,
                    componentInstanceId = componentInstanceId
                )
            }
        }
        return
    }
    if (currentState.isLoading) return
    cancelAllLoadingJobs()
    messageLoadingJob = scope.launch {
        ChatConversationLog.logViewportState(
            event = "scroll_to_bottom_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "isComments=$isComments"
        )
        _state.update {
            it.copy(
                isLoading = true,
                isOldestLoaded = false,
                isLatestLoaded = false,
                pendingScrollCommand = null,
                viewportPhase = ChatViewportPhase.Initializing
            )
        }
        ChatConversationLog.logViewportState(
            event = "scroll_to_bottom_reload_start",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "isComments=$isComments"
        )
        try {
            if (isComments && threadId != null) {
                loadComments(
                    targetChatId = targetChatId,
                    threadId = threadId,
                    scrollCommand = ChatScrollCommand.ScrollToBottom(animated = true)
                )
            } else {
                loadBottomMessages(
                    targetChatId = targetChatId,
                    threadId = threadId,
                    scrollCommand = ChatScrollCommand.ScrollToBottom(animated = true)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatConversationLog.logViewportState(
                event = "scroll_to_bottom_reload_failed",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "error=${e.javaClass.simpleName}"
            )
            Log.e("DefaultChatComponent", "Failed to scroll to bottom", e)
        } finally {
            _state.update { it.copy(isLoading = false) }
            ChatConversationLog.logViewportState(
                event = "scroll_to_bottom_reload_finish",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
        }
    }
}

internal fun DefaultChatComponent.cancelAllLoadingJobs() {
    messageLoadingJob?.cancel()
    loadMoreJob?.cancel()
    loadNewerJob?.cancel()
    inFlightOlderAnchorId = 0L
    inFlightNewerAnchorId = 0L
}

internal fun DefaultChatComponent.setupMessageCollectors() {
    repositoryMessage.newMessageFlow
        .onEach { message ->
            if (message.chatId == chatId || message.chatId == activeThreadChatId()) {
                if (resolveRemappedMessageId(message.id) != message.id) return@onEach
                val isCorrectThread = _state.value.isMessageInActiveThread(chatId, message)
                if (isCorrectThread) {
                    _state.update { state ->
                        state.withIncomingUnreadMessage(
                            rootChatId = chatId,
                            message = message
                        )
                    }
                    updateMessages(listOf(message))
                    requestSenderRefreshIfNeeded(message)
                    _state.update { state ->
                        state.copy(
                            isLatestLoaded = if (message.isOutgoing || state.isAtBottom) true else state.isLatestLoaded
                        )
                    }
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageIdUpdateFlow
        .onEach { event ->
            val cId = event.chatId
            val oldId = event.oldMessageId
            val newMessage = event.message
            if (cId == chatId || cId == activeThreadChatId()) {
                if (oldId != newMessage.id) {
                    remappedMessageIds[oldId] = newMessage.id
                } else {
                    remappedMessageIds.remove(oldId)
                }
                messageMutex.withLock {
                    _state.update { state ->
                        val isCorrectThread =
                            state.isMessageInActiveThread(chatId, newMessage)
                        if (!isCorrectThread) return@update state

                        val withoutOldId = state.messages.filterNot { it.id == oldId }
                        val canInsert = state.isAtBottom || state.isLatestLoaded || newMessage.isOutgoing

                        val updatedMessages = when {
                            withoutOldId.any { it.id == newMessage.id } -> {
                                withoutOldId.map { existing ->
                                    if (existing.id == newMessage.id) {
                                        mergeSenderVisuals(existing, newMessage)
                                    } else {
                                        existing
                                    }
                                }
                            }

                            canInsert -> withoutOldId + newMessage
                            else -> withoutOldId
                        }

                        val isComments = state.rootMessage != null
                        val distinctMessages = updatedMessages.distinctBy { it.id }
                        val sortedMessages = if (isComments) {
                            distinctMessages.sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
                        } else {
                            distinctMessages.sortedWith(compareByDescending<MessageModel> { it.date }.thenByDescending { it.id })
                        }
                        state.copy(
                            messages = sortedMessages,
                            isLatestLoaded = if (newMessage.isOutgoing || state.isAtBottom) true else state.isLatestLoaded
                        )
                    }
                }

                val isCurrentThread = _state.value.isMessageInActiveThread(chatId, newMessage)
                if (isCurrentThread) {
                    requestSenderRefreshIfNeeded(newMessage)
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageUploadProgressFlow
        .onEach { event ->
            if (event.chatId != chatId) return@onEach
            val messageId = event.messageId
            val progress = event.progress
            updateMessageContent(messageId) { message ->
                val isUploading = progress < 1f && message.sendingState is MessageSendingState.Pending
                val newSendingState = if (progress >= 1f) null else message.sendingState

                val newContent = when (val content = message.content) {
                    is MessageContent.Photo -> content.copy(isUploading = isUploading, uploadProgress = progress)
                    is MessageContent.Video -> content.copy(isUploading = isUploading, uploadProgress = progress)
                    is MessageContent.VideoNote -> content.copy(isUploading = isUploading, uploadProgress = progress)
                    is MessageContent.Document -> content.copy(isUploading = isUploading, uploadProgress = progress)
                    is MessageContent.Gif -> content.copy(isUploading = isUploading, uploadProgress = progress)
                    is MessageContent.Voice -> content.copy(isUploading = isUploading, uploadProgress = progress)
                    else -> content
                }
                message.copy(content = newContent, sendingState = newSendingState)
            }
        }
        .launchIn(scope)

    repositoryMessage.messageDownloadFlow
        .onEach { event ->
            when (event) {
                is MessageDownloadEvent.Progress -> {
                    if (event.chatId != chatId) return@onEach
                    updateMessageContent(event.messageId) { message ->
                        val isDownloading = event.progress < 1f
                        val newContent = when (val content = message.content) {
                            is MessageContent.Photo -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            is MessageContent.Video -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            is MessageContent.VideoNote -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            is MessageContent.Document -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            is MessageContent.Gif -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            is MessageContent.Voice -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            is MessageContent.Sticker -> content.copy(
                                isDownloading = isDownloading,
                                downloadProgress = event.progress,
                                downloadError = false
                            )

                            else -> content
                        }
                        message.copy(content = newContent)
                    }
                }

                is MessageDownloadEvent.Cancelled -> {
                    if (event.chatId != chatId) return@onEach
                    var cancelledFileId = 0
                    updateMessageContent(event.messageId) { message ->
                        val newContent = when (val content = message.content) {
                            is MessageContent.Photo -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            is MessageContent.Video -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            is MessageContent.VideoNote -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            is MessageContent.Document -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            is MessageContent.Gif -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            is MessageContent.Voice -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            is MessageContent.Sticker -> {
                                cancelledFileId = content.fileId
                                content.copy(
                                    isDownloading = false,
                                    downloadProgress = 0f,
                                    downloadError = false
                                )
                            }

                            else -> content
                        }
                        message.copy(content = newContent)
                    }
                    AutoDownloadSuppression.suppress(cancelledFileId)
                    if (cancelledFileId != 0) {
                        mediaDownloadRetryCount.remove(cancelledFileId)
                    }
                }

                is MessageDownloadEvent.Completed -> {
                    if (event.chatId != chatId) return@onEach
                    val messageId = event.messageId
                    val downloadedFileId = event.fileId
                    val path = event.path
                    var fileIdToRetry: Int? = null
                    var mainFileId = 0
                    var mainPathUpdated = false

                    updateMessageContent(messageId) { message ->
                        val isError = path.isEmpty()
                        val finalPath = path.ifEmpty { null }

                        val newContent = when (val content = message.content) {
                            is MessageContent.Photo -> {
                                val isMainPhotoFile =
                                    downloadedFileId == content.fileId ||
                                            (content.originalFileId != 0 && downloadedFileId == content.originalFileId)
                                if (isMainPhotoFile) {
                                    mainFileId = downloadedFileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = downloadedFileId
                                    content.copy(
                                        path = finalPath ?: content.path,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    if (finalPath != null) content.copy(thumbnailPath = finalPath) else content
                                }
                            }

                            is MessageContent.Video -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = content.fileId
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    if (finalPath != null) content.copy(thumbnailPath = finalPath) else content
                                }
                            }

                            is MessageContent.VideoNote -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = content.fileId
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    content
                                }
                            }

                            is MessageContent.Document -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = content.fileId
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    content
                                }
                            }

                            is MessageContent.Gif -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = content.fileId
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    content
                                }
                            }

                            is MessageContent.Voice -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = content.fileId
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    content
                                }
                            }

                            is MessageContent.Sticker -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
                                    if (isError) fileIdToRetry = content.fileId
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    content
                                }
                            }

                            else -> content
                        }
                        message.copy(content = newContent)
                    }

                    if (path.isNotEmpty() && mainFileId != 0) {
                        AutoDownloadSuppression.clear(mainFileId)
                        mediaDownloadRetryCount.remove(mainFileId)
                    }

                    if (mainPathUpdated && path.isNotEmpty()) {
                        updateFullScreenImagePath(messageId, path)
                    }

                    if (path.isNotEmpty() && messageId in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        updateInlineResultsWithFile(messageId.toInt(), path)
                    }

                    if (path.isNotEmpty() && messageId == downloadedFileId.toLong()) {
                        refreshCachedSenderProfiles(_state.value.messages)
                    }

                    fileIdToRetry?.let {
                        if (it != 0) {
                            val suppressed = AutoDownloadSuppression.isSuppressed(it)
                            if (!suppressed) {
                                val attempts = (mediaDownloadRetryCount[it] ?: 0) + 1
                                mediaDownloadRetryCount[it] = attempts
                                if (attempts <= MAX_DOWNLOAD_RETRIES) {
                                    onDownloadFile(it)
                                } else {
                                    AutoDownloadSuppression.suppress(it)
                                    Log.w(
                                        "DownloadDebug",
                                        "retryLimitReached: fileId=$it attempts=$attempts chatId=$chatId"
                                    )
                                }
                            } else {
                                Log.d(
                                    "DownloadDebug",
                                    "retrySkippedBySuppression: fileId=$it chatId=$chatId"
                                )
                            }
                        }
                    }
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageDeletedFlow
        .onEach { event ->
            val cId = event.chatId
            val messageIds = event.messageIds
            if (cId == chatId || cId == activeThreadChatId()) {
                messageIds.forEach(reactionUpdateSuppressedUntil::remove)
                messageIds.forEach(remappedMessageIds::remove)
                remappedMessageIds.entries.removeIf { (_, mappedId) -> mappedId in messageIds }
                _state.update { currentState ->
                    val currentMessages = currentState.messages.toMutableList()
                    val removed = currentMessages.removeAll { messageIds.contains(it.id) }
                    if (removed) {
                        currentState.copy(
                            messages = currentMessages,
                            pendingEditedMessageIds = currentState.pendingEditedMessageIds - messageIds.toSet()
                        )
                    } else {
                        currentState
                    }
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageEditedFlow
        .onEach { message ->
            if (_state.value.isMessageInActiveThread(chatId, message) || message.chatId == chatId) {
                messageMutex.withLock {
                    val targetMessageId = resolveRemappedMessageId(message.id)
                    _state.update { currentState ->
                        val now = System.currentTimeMillis()
                        val suppressUntil = reactionUpdateSuppressedUntil[targetMessageId]
                        val suppressReactionUpdate = suppressUntil != null && now < suppressUntil

                        if (!suppressReactionUpdate && suppressUntil != null) {
                            reactionUpdateSuppressedUntil.remove(targetMessageId, suppressUntil)
                        }

                        currentState.withUpdatedMessage(targetMessageId) { current ->
                            val mediaSafeMessage = when {
                                current.content is MessageContent.Photo && message.content is MessageContent.Photo -> {
                                    val currentPhoto = current.content as MessageContent.Photo
                                    val incomingPhoto = message.content as MessageContent.Photo
                                    if (currentPhoto.fileId == incomingPhoto.fileId) {
                                        message.copy(
                                            content = incomingPhoto.copy(
                                                path = incomingPhoto.path ?: currentPhoto.path,
                                                thumbnailPath = incomingPhoto.thumbnailPath
                                                    ?: currentPhoto.thumbnailPath
                                            )
                                        )
                                    } else {
                                        message
                                    }
                                }

                                current.content is MessageContent.Video && message.content is MessageContent.Video -> {
                                    val currentVideo = current.content as MessageContent.Video
                                    val incomingVideo = message.content as MessageContent.Video
                                    if (currentVideo.fileId == incomingVideo.fileId) {
                                        message.copy(
                                            content = incomingVideo.copy(
                                                path = incomingVideo.path ?: currentVideo.path,
                                                thumbnailPath = incomingVideo.thumbnailPath
                                                    ?: currentVideo.thumbnailPath
                                            )
                                        )
                                    } else {
                                        message
                                    }
                                }

                                else -> message
                            }

                            when {
                                suppressReactionUpdate -> mediaSafeMessage.copy(reactions = current.reactions)
                                reactionsSemanticEqual(
                                    current.reactions,
                                    mediaSafeMessage.reactions
                                ) -> mediaSafeMessage.copy(reactions = current.reactions)

                                else -> mediaSafeMessage
                            }
                        }.clearPendingEditedMessage(targetMessageId)
                    }
                }
                handleEditedRichMessage(message)
            }
        }
        .launchIn(scope)

    repositoryMessage.mediaUpdateFlow
        .onEach {
            loadChatInfo()
        }
        .launchIn(scope)

    repositoryMessage.messageReadFlow
        .onEach { readUpdate ->
            if (readUpdate.chatId == chatId || readUpdate.chatId == activeThreadChatId()) {
                _state.update { currentState ->
                    when (readUpdate) {
                        is ReadUpdate.Inbox -> currentState.withInboxReadUpdate(
                            readChatId = readUpdate.chatId,
                            readMessageId = readUpdate.messageId,
                            updateUnreadSession = readUpdate.chatId == chatId
                        )

                        is ReadUpdate.Outbox -> {
                            var hasChanges = false
                            val updatedMessages = currentState.messages.map { message ->
                                if (message.chatId == readUpdate.chatId &&
                                    message.isOutgoing &&
                                    !message.isRead &&
                                    message.id <= readUpdate.messageId
                                ) {
                                    hasChanges = true
                                    message.copy(isRead = true)
                                } else {
                                    message
                                }
                            }
                            if (hasChanges) {
                                currentState.copy(messages = updatedMessages)
                            } else {
                                currentState
                            }
                        }
                    }
                }
            }
        }
        .launchIn(scope)

    observeSenderUpdates()
}

private fun DefaultChatComponent.observeSenderUpdates() {
    repositoryMessage.senderUpdateFlow
        .onEach { senderId ->
            if (senderId <= 0L) return@onEach
            val hasAffectedMessages = _state.value.messages.any { it.senderId == senderId }
            if (!hasAffectedMessages) return@onEach

            repositoryMessage.invalidateSenderCache(senderId)
            val user = userRepository.getUser(senderId) ?: return@onEach
            refreshMessagesForSender(senderId, user)
        }
        .launchIn(scope)
}

private fun DefaultChatComponent.refreshCachedSenderProfiles(messages: List<MessageModel>) {
    val senderIds = messages.asSequence()
        .filter { it.needsSenderRefresh() }
        .map { it.senderId }
        .filter { it > 0L }
        .distinct()
        .toList()

    senderIds.forEach { senderId ->
        requestSenderRefresh(senderId)
    }
}

private fun DefaultChatComponent.refreshMessagesForSender(senderId: Long, user: UserModel) {
    val fullName = listOfNotNull(
        user.firstName.takeIf { it.isNotBlank() },
        user.lastName?.takeIf { it.isNotBlank() }
    ).joinToString(" ").ifBlank { "" }

    _state.update { currentState ->
        val updatedMessages = currentState.messages.map { message ->
            if (message.senderId == senderId) {
                val resolvedAvatar = firstUsableAvatarPath(
                    user.avatarPath,
                    user.personalAvatarPath,
                    message.senderAvatar,
                    message.senderPersonalAvatar
                )
                val resolvedPersonalAvatar = firstUsableAvatarPath(
                    user.personalAvatarPath,
                    user.avatarPath,
                    message.senderPersonalAvatar,
                    message.senderAvatar
                )

                message.copy(
                    senderName = fullName,
                    senderAvatar = resolvedAvatar,
                    senderPersonalAvatar = resolvedPersonalAvatar,
                    isSenderVerified = user.isVerified,
                    isSenderPremium = user.isPremium,
                    senderStatusEmojiId = user.statusEmojiId,
                    senderStatusEmojiPath = user.statusEmojiPath ?: message.senderStatusEmojiPath
                )
            } else {
                message
            }
        }
        currentState.copy(messages = updatedMessages)
    }
}

private fun DefaultChatComponent.updateInlineResultsWithFile(fileId: Int, newPath: String) {
    _state.update { currentState ->
        val currentResults = currentState.inlineBotResults ?: return@update currentState
        val updatedResults = currentResults.results.map { result ->
            if (result.thumbFileId == fileId) result.copy(thumbUrl = newPath) else result
        }
        currentState.copy(inlineBotResults = currentResults.copy(results = updatedResults))
    }
}

private fun DefaultChatComponent.updateFullScreenImagePath(messageId: Long, newPath: String) {
    _state.update { currentState ->
        val currentImages = currentState.fullScreenImages ?: return@update currentState
        val index = currentState.fullScreenImageMessageIds.indexOf(messageId)
        if (index !in currentImages.indices) {
            return@update currentState
        }

        val updated = currentImages.toMutableList().apply { this[index] = newPath }
        currentState.copy(fullScreenImages = updated)
    }
}

private inline fun DefaultChatComponent.updateMessageContent(
    messageId: Long,
    noinline transform: (MessageModel) -> MessageModel
) {
    scope.launch {
        messageMutex.withLock {
            val targetMessageId = resolveRemappedMessageId(messageId)
            _state.update { currentState ->
                currentState.withUpdatedMessage(targetMessageId, transform)
            }
        }
    }
}

internal fun DefaultChatComponent.handleEditedRichMessage(message: MessageModel) {
    richMessageCoordinator.onMessageEdited(message)
}

internal fun DefaultChatComponent.loadDraft() {
    scope.launch {
        _state.value.currentTopicId
        val draft = repositoryMessage.getChatDraft(activeThreadChatId(), activeThreadId())
        if (!draft.isNullOrEmpty()) {
            recomputeDraftLinkPreview(
                text = draft,
                updateDraftText = true
            )
        }
    }
}

internal fun DefaultChatComponent.handleTopicClick(topicId: Int) {
    val id = if (topicId == 0) null else topicId.toLong()
    ChatConversationLog.logViewportState(
        event = "topic_click",
        state = _state.value,
        componentInstanceId = componentInstanceId,
        extra = "topicId=$topicId resolvedTopicId=${id ?: 0L}"
    )
    resetSearchState(isSearchActive = false)
    ChatConversationLog.logViewportState(
        event = "topic_click_reset_before",
        state = _state.value,
        componentInstanceId = componentInstanceId,
        extra = "topicId=$topicId resolvedTopicId=${id ?: 0L}"
    )
    _state.update {
        it.copy(
            currentTopicId = id,
            currentThreadChatId = null,
            currentMessageThreadId = null,
            messages = emptyList(),
            isOldestLoaded = false,
            isLatestLoaded = false,
            rootMessage = null,
            isAtBottom = id == null,
            pendingScrollCommand = null,
            viewportPhase = ChatViewportPhase.Initializing
        )
    }
    ChatConversationLog.logViewportState(
        event = "topic_click_state_reset",
        state = _state.value,
        componentInstanceId = componentInstanceId,
        extra = "topicId=$topicId"
    )
    if (topicId != 0) {
        scope.launch {
            forumTopicsRepository.markForumTopicAsRead(chatId, topicId)
        }
    }
    loadMessages(force = true)
}

internal fun DefaultChatComponent.handleCommentsClick(messageId: Long) {
    scope.launch {
        ChatConversationLog.logViewportState(
            event = "comments_click",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messageId=$messageId"
        )
        val message = _state.value.messages.find { it.id == messageId }
        val threadContext = repositoryMessage.getMessageThreadContext(chatId, messageId)
        resetSearchState(isSearchActive = false)
        ChatConversationLog.logViewportState(
            event = "comments_click_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messageId=$messageId threadChatId=${threadContext?.chatId ?: 0L} threadId=${threadContext?.threadId ?: messageId}"
        )
        _state.update {
            it.copy(
                currentTopicId = messageId,
                currentThreadChatId = threadContext?.chatId,
                currentMessageThreadId = threadContext?.threadId ?: messageId,
                rootMessage = message,
                messages = emptyList(),
                isOldestLoaded = false,
                isLatestLoaded = false,
                isAtBottom = false,
                pendingScrollCommand = null,
                viewportPhase = ChatViewportPhase.Initializing
            )
        }
        ChatConversationLog.logViewportState(
            event = "comments_click_state_reset",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messageId=$messageId threadChatId=${threadContext?.chatId ?: 0L} threadId=${threadContext?.threadId ?: messageId}"
        )
        loadComments(
            targetChatId = threadContext?.chatId ?: chatId,
            threadId = threadContext?.threadId ?: messageId,
            scrollCommand = ChatScrollCommand.ScrollToStart(animated = false)
        )
    }
}
