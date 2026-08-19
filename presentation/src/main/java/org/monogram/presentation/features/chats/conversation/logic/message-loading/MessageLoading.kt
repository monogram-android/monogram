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
import org.monogram.domain.models.ChatViewportCacheEntry
import org.monogram.domain.models.ConversationUpdate
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageDownloadEvent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageHistorySnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.MessageSendingState
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.BoundaryState
import org.monogram.domain.repository.ConversationPipelineMode
import org.monogram.domain.repository.ConversationScope
import org.monogram.domain.repository.HistoryAnchor
import org.monogram.domain.repository.HistoryDirection
import org.monogram.domain.repository.HistoryRequest
import org.monogram.domain.repository.HistorySource
import org.monogram.domain.repository.HistoryPage
import org.monogram.domain.repository.ReadUpdate
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.presentation.features.chats.conversation.AutoDownloadSuppression
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.ChatConversationLog
import org.monogram.presentation.features.chats.conversation.ChatScrollCommand
import org.monogram.presentation.features.chats.conversation.ChatViewportPhase
import org.monogram.presentation.features.chats.conversation.ConversationLoadSession
import org.monogram.presentation.features.chats.conversation.ConversationPipelineFallbackGate
import org.monogram.presentation.features.chats.conversation.ConversationSessionState
import org.monogram.presentation.features.chats.conversation.ConversationViewportReducer
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent
import org.monogram.presentation.features.chats.conversation.OutgoingMessageReducer
import org.monogram.presentation.features.chats.conversation.ScrollAlign
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds


private const val PAGE_SIZE = 50
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

private suspend fun DefaultChatComponent.filterHistoryMessages(
    newMessages: List<MessageModel>,
): List<MessageModel> {
    val currentState = _state.value
    val adBlockEnabled = appPreferences.isAdBlockEnabled.value
    val keywords = appPreferences.adBlockKeywords.value
    val whitelistedChannels = appPreferences.adBlockWhitelistedChannels.value
    val isChannel = currentState.isChannel
    val isWhitelisted = whitelistedChannels.contains(chatId)

    return if (adBlockEnabled && isChannel && !isWhitelisted) {
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
}

internal fun ChatComponent.State.mergeHistoryMessages(
    filteredNewMessages: List<MessageModel>,
    replace: Boolean
): List<MessageModel> {
    if (filteredNewMessages.isEmpty()) {
        return if (replace && messages.any { it.sendingState is MessageSendingState.Pending }) {
            messages.filter { it.sendingState is MessageSendingState.Pending }
        } else {
            messages
        }
    }

    val currentList = if (replace) {
        messages.filter { it.sendingState is MessageSendingState.Pending }
    } else {
        messages
    }
    val existingReactionsById = if (replace) {
        messages.filter { it.reactions.isNotEmpty() }.associate { it.id to it.reactions }
    } else {
        emptyMap()
    }
    val previousMessagesById = if (replace) messages.associateBy { it.id } else emptyMap()

    val isComments = rootMessage != null

    val messageMap = LinkedHashMap<Long, MessageModel>(currentList.size + filteredNewMessages.size)
    currentList.forEach { messageMap[it.id] = it }

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
        val contentSafeMessage = preservePendingEditedContent(
            incomingMessage = restoredMessage,
            previousMessage = previous
        )
        messageMap[msg.id] = contentSafeMessage
    }

    return messageMap.values.let {
        val sortedMessages = if (isComments) {
            it.sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
        } else {
            it.sortedWith(compareByDescending<MessageModel> { it.date }.thenByDescending { it.id })
        }
        pruneDeliveredPendingDuplicates(sortedMessages)
    }
}

internal suspend fun DefaultChatComponent.updateMessages(newMessages: List<MessageModel>, replace: Boolean = false) {
    val filteredNewMessages = filterHistoryMessages(newMessages)
    messageMutex.withLock {
        val current = _state.value
        val mergedMessages = current.mergeHistoryMessages(filteredNewMessages, replace)
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            val sessionState = conversationSession.applySnapshot(
                generation = loadingGeneration,
                messages = mergedMessages,
                ascending = current.rootMessage != null
            )
            if (!sessionState.closed) {
                _state.update { current ->
                    if (current.messages == sessionState.messages) current
                    else current.copy(
                        messages = sessionState.messages,
                        outgoingMessageStates = current.outgoingMessageStates +
                                OutgoingMessageReducer.recover(sessionState.messages)
                    )
                }
                if (sessionState.messages.isNotEmpty()) {
                    ChatOpenPerfBridge.markFirstContent(chatId, current.effectiveThreadId())
                }
            }
            return@withLock
        }

        if (mergedMessages != current.messages) {
            _state.update { state ->
                state.copy(
                    messages = mergedMessages,
                    outgoingMessageStates = state.outgoingMessageStates +
                            OutgoingMessageReducer.recover(mergedMessages)
                )
            }
            if (mergedMessages.isNotEmpty()) {
                ChatOpenPerfBridge.markFirstContent(chatId, current.effectiveThreadId())
            }
        }
        if (conversationPipelineMode == ConversationPipelineMode.Shadow) {
            val legacyMessages = _state.value.messages
            val sessionState = conversationSession.applySnapshot(
                generation = loadingGeneration,
                messages = legacyMessages,
                ascending = _state.value.rootMessage != null
            )
            val shadowIds = sessionState.messages.map(MessageModel::id)
            val legacyIds = legacyMessages.map(MessageModel::id)
            if (shadowIds != legacyIds) {
                ChatOpenPerfBridge.recordShadowMismatch(chatId, current.effectiveThreadId())
                ConversationPipelineFallbackGate.requestFallback()
                Log.w(
                    "ConversationSession",
                    "shadow_mismatch type=ids_or_order legacyCount=${legacyIds.size} shadowCount=${shadowIds.size}"
                )
            }
        }
    }
}

internal fun ChatComponent.State.withConversationSessionUpdate(
    sessionState: ConversationSessionState,
    update: ConversationUpdate,
    rootChatId: Long,
    suppressReactionUpdate: Boolean = false
): ChatComponent.State {
    val stateWithSideEffects = when (update) {
        is ConversationUpdate.Upsert -> {
            val withUnread = if (update.isNew) {
                withIncomingUnreadMessage(rootChatId, update.message)
            } else {
                this
            }
            if (update.isNew) withUnread else withUnread.clearPendingEditedMessage(update.message.id)
        }

        is ConversationUpdate.InboxRead -> withInboxReadUpdate(
            readChatId = update.chatId,
            readMessageId = update.lastReadMessageId,
            updateUnreadSession = update.chatId == rootChatId
        )

        is ConversationUpdate.Delete -> copy(
            pendingEditedMessageIds = pendingEditedMessageIds - update.messageIds
        )

        is ConversationUpdate.ReplaceTemporaryId,
        is ConversationUpdate.OutboxRead,
        is ConversationUpdate.SendAcknowledged,
        is ConversationUpdate.SendFailed -> this
    }

    val previousByKey = messages.associateBy { it.chatId to it.id }
    val projectedMessages = sessionState.messages.map { incoming ->
        val previous = previousByKey[incoming.chatId to incoming.id]
            ?: (update as? ConversationUpdate.ReplaceTemporaryId)
                ?.takeIf { incoming.chatId == it.chatId && incoming.id == it.message.id }
                ?.let { previousByKey[it.chatId to it.temporaryMessageId] }
            ?: return@map incoming
        val preserveSendingState = update !is ConversationUpdate.ReplaceTemporaryId &&
                update !is ConversationUpdate.SendFailed
        previous.projectRuntimeFields(
            incoming = incoming,
            preserveSendingState = preserveSendingState,
            preserveReactions = suppressReactionUpdate &&
                    update is ConversationUpdate.Upsert && incoming.id == update.message.id,
            pendingEditedMessageIds = stateWithSideEffects.pendingEditedMessageIds
        )
    }

    val marksLatest = when (update) {
        is ConversationUpdate.Upsert -> update.isNew &&
                (update.message.isOutgoing || stateWithSideEffects.isAtBottom)

        is ConversationUpdate.ReplaceTemporaryId ->
            update.message.isOutgoing || stateWithSideEffects.isAtBottom

        else -> false
    }
    return stateWithSideEffects.copy(
        messages = projectedMessages,
        outgoingMessageStates = sessionState.outgoingMessageStates,
        isLatestLoaded = if (marksLatest) true else stateWithSideEffects.isLatestLoaded
    )
}

internal fun applyConversationUpdateBookkeeping(
    update: ConversationUpdate,
    remappedMessageIds: MutableMap<Long, Long>,
    reactionUpdateSuppressedUntil: MutableMap<Long, Long>
) {
    when (update) {
        is ConversationUpdate.ReplaceTemporaryId -> {
            if (update.temporaryMessageId != update.message.id) {
                remappedMessageIds[update.temporaryMessageId] = update.message.id
            } else {
                remappedMessageIds.remove(update.temporaryMessageId)
            }
        }

        is ConversationUpdate.Delete -> {
            update.messageIds.forEach(reactionUpdateSuppressedUntil::remove)
            update.messageIds.forEach(remappedMessageIds::remove)
            remappedMessageIds.entries.removeIf { (_, mappedId) -> mappedId in update.messageIds }
        }

        is ConversationUpdate.Upsert,
        is ConversationUpdate.InboxRead,
        is ConversationUpdate.OutboxRead,
        is ConversationUpdate.SendAcknowledged,
        is ConversationUpdate.SendFailed -> Unit
    }
}

private fun MessageModel.projectRuntimeFields(
    incoming: MessageModel,
    preserveSendingState: Boolean,
    preserveReactions: Boolean,
    pendingEditedMessageIds: Set<Long>
): MessageModel {
    val senderSafe = mergeSenderVisuals(this, incoming).copy(reactions = incoming.reactions)
    val mediaSafe = senderSafe.copy(
        content = content.projectMediaRuntime(senderSafe.content),
        sendingState = if (preserveSendingState) sendingState else senderSafe.sendingState
    )
    val editSafe = if (id in pendingEditedMessageIds) {
        mediaSafe.copy(content = content)
    } else {
        mediaSafe
    }
    return when {
        preserveReactions -> editSafe.copy(reactions = reactions)
        reactionsSemanticEqual(
            reactions,
            editSafe.reactions
        ) -> editSafe.copy(reactions = reactions)

        else -> editSafe
    }
}

private fun MessageContent.projectMediaRuntime(incoming: MessageContent): MessageContent = when {
    this is MessageContent.Photo && incoming is MessageContent.Photo && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        thumbnailPath = incoming.thumbnailPath ?: thumbnailPath,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.Video && incoming is MessageContent.Video && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        thumbnailPath = incoming.thumbnailPath ?: thumbnailPath,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.VideoNote && incoming is MessageContent.VideoNote && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        thumbnail = incoming.thumbnail ?: thumbnail,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.Document && incoming is MessageContent.Document && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.Audio && incoming is MessageContent.Audio && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.Voice && incoming is MessageContent.Voice && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.Sticker && incoming is MessageContent.Sticker && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    this is MessageContent.Gif && incoming is MessageContent.Gif && fileId == incoming.fileId -> incoming.copy(
        path = incoming.path ?: path,
        isUploading = isUploading,
        uploadProgress = uploadProgress,
        isDownloading = isDownloading,
        downloadProgress = downloadProgress,
        downloadError = downloadError
    )

    else -> incoming
}

private suspend fun DefaultChatComponent.loadHistoryPage(request: HistoryRequest) = try {
    if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) {
        loadMtProtoHistorySnapshot(request)
    } else if (conversationPipelineMode == ConversationPipelineMode.Legacy) {
        repositoryMessage.getHistoryPage(request)
    } else {
        conversationSession.loadHistory(loadingGeneration, request)
    }
} catch (error: Exception) {
    if (error !is CancellationException && conversationPipelineMode != ConversationPipelineMode.Legacy) {
        ConversationPipelineFallbackGate.requestFallback()
    }
    throw error
}

private suspend fun DefaultChatComponent.loadMtProtoHistorySnapshot(request: HistoryRequest): HistoryPage {
    require(request.key.scope == ConversationScope.Main) {
        "MTProto snapshot history supports only main conversations"
    }
    require(request.direction == org.monogram.domain.repository.HistoryDirection.Initial) {
        "MTProto snapshot history currently supports only initial pages"
    }
    val chat = chatListRepository.getChatById(request.key.chatId)
    val peer = TelegramPeerChatId.decode(request.key.chatId, chat?.isChannel)
    val page = messageHistorySnapshotRepository.getHistory(
        MessageHistorySnapshotRequest(
            accountId = "default",
            peerType = peer.type,
            peerId = peer.id,
            before = null,
            limit = request.limit,
        )
    )
    return HistoryPage(
        messages = page.messages.map { it.toMessageModel(request.key.chatId) },
        olderBoundary = if (page.nextCursor == null) BoundaryState.Reached else BoundaryState.Open,
        newerBoundary = BoundaryState.Reached,
        source = HistorySource.RoomSnapshot,
    )
}

private fun MessageHistorySnapshotModel.toMessageModel(chatId: Long) = MessageModel(
    id = messageId,
    date = date,
    isOutgoing = isOutgoing,
    senderName = "",
    chatId = chatId,
    content = if (isService) MessageContent.Service(text.orEmpty()) else MessageContent.Text(text.orEmpty()),
    senderId = senderId ?: 0L,
    editDate = editDate ?: 0,
    mediaAlbumId = groupedId ?: 0L,
    isPinned = isPinned,
    hasUnreadMention = isMentioned,
)

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
    val generation = loadingGeneration
    messageLoadingJob = scope.launch {
        val openStartedAt = System.currentTimeMillis()
        ChatConversationLog.logViewportState(
            event = "load_messages_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "source=$loadSource force=$force"
        )
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            conversationSession.setOperationLoading(
                generation,
                loading = true,
                resetBoundaries = true
            )
        }
        _state.update {
            ConversationViewportReducer.beginLoad(
                state = it,
                legacyOwnsLoadingState = conversationPipelineMode != ConversationPipelineMode.New
            )
        }
        ChatConversationLog.logViewportState(
            event = "load_messages_started",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "source=$loadSource force=$force"
        )

        try {
            check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
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
                    check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
                    loadHistoryPage(
                        HistoryRequest(
                            key = historyConversationKey(targetChatId, threadId),
                            anchor = HistoryAnchor.Message(lastRead),
                            direction = HistoryDirection.Newer,
                            limit = 1,
                            source = HistorySource.TdlibNetwork
                        )
                    )
                        .messages
                        .firstOrNull()
                        ?.id
                        ?: lastRead.takeIf { it > 0L }
                }

            val target = resolveInitialChatScrollTarget(
                chatId = targetChatId,
                    explicitMessageId = currentState.scrollToMessageId,
                    savedViewport = savedViewport,
                    firstUnreadMessageId = firstUnreadId,
                    unreadCount = unreadSeparatorCount,
                    backfillUnreadThreshold = PAGE_SIZE,
                isComments = isComments
            )
            check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
            ChatConversationLog.logViewportState(
                event = "load_messages_target_resolved",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "source=$loadSource target=${target.perfTargetName()} targetChatId=$targetChatId targetAnchor=${target.anchorMessageId ?: 0L} savedAnchor=${savedViewport?.anchorMessageId ?: savedViewport?.anchorAliasIds?.firstOrNull() ?: 0L} firstUnreadId=${firstUnreadId ?: 0L}"
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
                    check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
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
            if (isActive && isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    conversationSession.setOperationLoading(generation, loading = false)
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
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
    val olderPage = loadHistoryPage(
        HistoryRequest(
            key = historyConversationKey(targetChatId, threadId),
            anchor = HistoryAnchor.Latest,
            direction = HistoryDirection.Initial,
            limit = PAGE_SIZE,
            source = HistorySource.TdlibNetwork
        )
    )
    val messages = olderPage.messages
    val reachedOldest = olderPage.olderBoundary is BoundaryState.Reached

    if (conversationPipelineMode == ConversationPipelineMode.New) {
        conversationSession.setBoundaries(
            generation = loadingGeneration,
            oldestLoaded = reachedOldest,
            latestLoaded = true
        )
    }

    _state.update {
        ConversationViewportReducer.contentReady(
            state = it,
            isAtBottom = when (scrollCommand) {
                is ChatScrollCommand.ScrollToBottom -> true
                is ChatScrollCommand.RestoreViewport -> scrollCommand.atBottom
                else -> false
            },
            legacyOldestLoaded = reachedOldest.takeIf {
                conversationPipelineMode != ConversationPipelineMode.New
            },
            legacyLatestLoaded = true.takeIf {
                conversationPipelineMode != ConversationPipelineMode.New
            }
        )
    }
    updateMessages(messages, replace = true)
    refreshCachedSenderProfiles(messages)
    if (scrollCommand != null) {
        restoreViewport(scrollCommand)
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
    var previewMessages = emptyList<MessageModel>()
    val cachedMessages = loadHistoryPage(
        HistoryRequest(
            key = historyConversationKey(targetChatId, threadId),
            anchor = HistoryAnchor.Latest,
            direction = HistoryDirection.Initial,
            limit = PAGE_SIZE,
            source = HistorySource.RoomSnapshot
        )
    ).messages
    if (cachedMessages.isNotEmpty()) {
        hasCachedPreview = true
        previewMessages = cachedMessages
        _state.update {
            ConversationViewportReducer.contentReady(
                state = it,
                isAtBottom = true,
                legacyOldestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                },
                legacyLatestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                }
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
        if (scrollCommand != null) {
            restoreViewport(scrollCommand)
            ChatConversationLog.logViewportState(
                event = "load_bottom_cached_restore",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "command=${scrollCommand.javaClass.simpleName}"
            )
        }
    }

    val localPage = loadHistoryPage(
        HistoryRequest(
            key = historyConversationKey(targetChatId, threadId),
            anchor = HistoryAnchor.Latest,
            direction = HistoryDirection.Initial,
            limit = PAGE_SIZE,
            source = HistorySource.TdlibLocal
        )
    )
    if (localPage.messages.isNotEmpty()) {
        hasCachedPreview = true
        previewMessages = localPage.messages
        updateMessages(localPage.messages, replace = true)
        refreshCachedSenderProfiles(localPage.messages)
        ChatConversationLog.logViewportState(
            event = "load_bottom_tdlib_local_preview",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetChatId=$targetChatId localMessages=${localPage.messages.size}"
        )
    }

    val olderPage = if (shouldRequestInitialNetwork(localPage, PAGE_SIZE, threadId)) {
        loadHistoryPage(
            HistoryRequest(
                key = historyConversationKey(targetChatId, threadId),
                anchor = HistoryAnchor.Latest,
                direction = HistoryDirection.Initial,
                limit = PAGE_SIZE,
                source = HistorySource.TdlibNetwork
            )
        )
    } else {
        localPage
    }
    val messages = olderPage.messages
    val isReconciliationSameAsPreview = hasCachedPreview && previewMessages.isNotEmpty() &&
            messages.size == previewMessages.size &&
            messages.zip(previewMessages).all { (reconciled, cached) ->
                reconciled.id == cached.id && !cached.hasUnresolvableCachedMedia()
            }

    val isOldestLoaded = if (isReconciliationSameAsPreview) {
        false
    } else {
        olderPage.olderBoundary is BoundaryState.Reached
    }

    if (conversationPipelineMode == ConversationPipelineMode.New) {
        conversationSession.setBoundaries(
            generation = loadingGeneration,
            oldestLoaded = isOldestLoaded,
            latestLoaded = true
        )
    }

    if (!isReconciliationSameAsPreview) {
        _state.update {
            ConversationViewportReducer.contentReady(
                state = it,
                isAtBottom = true,
                legacyOldestLoaded = isOldestLoaded.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                },
                legacyLatestLoaded = true.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                }
            )
        }
        val shouldReplaceCachedPreview = !hasCachedPreview || messages.isNotEmpty()
        updateMessages(messages, replace = shouldReplaceCachedPreview)
        refreshCachedSenderProfiles(messages)
        ChatConversationLog.logViewportState(
            event = "load_bottom_reconciliation_applied",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetChatId=$targetChatId source=${olderPage.source} messages=${messages.size} replace=${shouldReplaceCachedPreview} reachedOldest=${olderPage.olderBoundary is BoundaryState.Reached}"
        )
    } else {
        _state.update {
            ConversationViewportReducer.contentReady(
                state = it,
                isAtBottom = true,
                legacyOldestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                },
                legacyLatestLoaded = true.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                }
            )
        }
        ChatConversationLog.logViewportState(
            event = "load_bottom_reconciliation_same_as_preview",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "targetChatId=$targetChatId source=${olderPage.source} messages=${messages.size}"
        )
    }
    if (scrollCommand != null) {
        restoreViewport(scrollCommand)
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
    val cachedMessages = loadHistoryPage(
        HistoryRequest(
            key = historyConversationKey(chatId, threadId),
            anchor = HistoryAnchor.Message(messageId),
            direction = HistoryDirection.Around,
            limit = PAGE_SIZE,
            source = HistorySource.RoomSnapshot
        )
    ).messages
    if (cachedMessages.any { it.id == messageId }) {
        hasTargetPreview = true
        _state.update {
            ConversationViewportReducer.contentReady(
                state = it,
                isAtBottom = false,
                legacyOldestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                },
                legacyLatestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                }
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
            restoreViewport(scrollCommand)
            ChatConversationLog.logViewportState(
                event = "load_around_cached_restore",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "command=${scrollCommand.javaClass.simpleName} targetMessageId=$messageId"
            )
        }
    }

    val messages = loadHistoryPage(
        HistoryRequest(
            key = historyConversationKey(chatId, threadId),
            anchor = HistoryAnchor.Message(messageId),
            direction = HistoryDirection.Around,
            limit = PAGE_SIZE,
            source = HistorySource.TdlibNetwork
        )
    ).messages
    if (messages.isNotEmpty()) {
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            conversationSession.setBoundaries(
                generation = loadingGeneration,
                oldestLoaded = false,
                latestLoaded = false
            )
        }
        _state.update {
            ConversationViewportReducer.contentReady(
                state = it,
                isAtBottom = false,
                legacyOldestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                },
                legacyLatestLoaded = false.takeIf {
                    conversationPipelineMode != ConversationPipelineMode.New
                }
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
            restoreViewport(scrollCommand)
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
    _state.update { ConversationViewportReducer.requestHighlight(it, messageId) }
}

private fun DefaultChatComponent.restoreViewport(command: ChatScrollCommand) {
    _state.update { ConversationViewportReducer.restore(it, command) }
}

internal enum class ScrollToBottomHandling {
    Runtime,
    ReloadLatestWindow
}

internal fun ChatComponent.State.resolveScrollToBottomHandling(): ScrollToBottomHandling {
    return if (messages.isNotEmpty()) {
        ScrollToBottomHandling.Runtime
    } else {
        ScrollToBottomHandling.ReloadLatestWindow
    }
}

internal fun ChatComponent.State.enqueueRuntimeScrollToBottom(
    animated: Boolean = true
): ChatComponent.State {
    return ConversationViewportReducer.enqueue(
        state = copy(isAtBottom = true),
        command = ChatScrollCommand.ScrollToBottom(animated = animated)
    )
}

internal fun ChatComponent.State.withResetSavedBottomViewport(): ChatComponent.State {
    return copy(
        isAtBottom = true,
        lastSavedViewport = ChatViewportCacheEntry(atBottom = true, readFully = true),
        lastScrollPosition = 0L
    )
}

private fun DefaultChatComponent.queueJumpToLoadedMessage(
    messageId: Long,
    highlight: Boolean,
    align: ScrollAlign = ScrollAlign.Center,
    animated: Boolean = true
) {
    _state.update {
        ConversationViewportReducer.restore(
            state = it.copy(isAtBottom = false),
            command = ChatScrollCommand.JumpToMessage(
                messageId = messageId,
                highlight = highlight,
                align = align,
                animated = animated
            )
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

    val generation = loadingGeneration
    loadMoreJob = scope.launch {
        if (conversationPipelineMode != ConversationPipelineMode.New) {
            _state.update { it.copy(isLoadingOlder = true) }
        }
        try {
            check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
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
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    conversationSession.markBoundaryReached(generation, HistoryDirection.Older)
                } else {
                    _state.update { it.copy(isOldestLoaded = true) }
                }
                return@launch
            }

            var currentAnchorId = anchorId
            var isOldestLoaded = false
            var attempts = 0

            while (!isOldestLoaded && attempts < 5) {
                attempts++
                check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }

                val beforeSize = _state.value.messages.size
                val olderPage = loadHistoryPage(
                    HistoryRequest(
                        key = historyConversationKey(targetChatId, threadId),
                        anchor = HistoryAnchor.Message(currentAnchorId),
                        direction = HistoryDirection.Older,
                        limit = PAGE_SIZE,
                        source = HistorySource.TdlibNetwork
                    )
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

                val isRemote = olderPage.source == HistorySource.TdlibNetwork
                isOldestLoaded = olderPage.olderBoundary is BoundaryState.Reached ||
                        (isRemote && !hasOlderProgress)

                if (hasOlderProgress) {
                    lastLoadedOlderId = nextOlderAnchorId
                    currentAnchorId = nextOlderAnchorId
                }

                if (!isRemote && olderMessages.isEmpty()) {
                    break
                }

                if (isOldestLoaded || listGrew) break
            }

            if (isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    if (isOldestLoaded) {
                        conversationSession.markBoundaryReached(generation, HistoryDirection.Older)
                    }
                } else {
                    _state.update { it.copy(isOldestLoaded = isOldestLoaded) }
                }
            }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load more messages", e)
            lastLoadedOlderId = 0L
        } finally {
            inFlightOlderAnchorId = 0L
            if (isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode != ConversationPipelineMode.New) {
                    _state.update { it.copy(isLoadingOlder = false) }
                }
            }
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

    val generation = loadingGeneration
    loadNewerJob = scope.launch {
        if (conversationPipelineMode != ConversationPipelineMode.New) {
            _state.update { it.copy(isLoadingNewer = true) }
        }
        try {
            check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
            loadNewerMessagesPage()
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load newer messages", e)
            lastLoadedNewerId = 0L
        } finally {
            inFlightNewerAnchorId = 0L
            if (isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode != ConversationPipelineMode.New) {
                    _state.update { it.copy(isLoadingNewer = false) }
                }
            }
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
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            conversationSession.markBoundaryReached(loadingGeneration, HistoryDirection.Newer)
        } else {
            _state.update { it.copy(isLatestLoaded = true) }
        }
        return true
    }

    val newerPage = loadHistoryPage(
        HistoryRequest(
            key = historyConversationKey(targetChatId, threadId),
            anchor = HistoryAnchor.Message(anchorId),
            direction = HistoryDirection.Newer,
            limit = PAGE_SIZE,
            source = HistorySource.TdlibNetwork
        )
    )
    val newerMessages = newerPage.messages
    val isLatestLoaded =
        newerPage.source == HistorySource.TdlibNetwork &&
                (newerMessages.size < PAGE_SIZE ||
                        (newerMessages.isNotEmpty() && newerMessages.all { msg -> currentMessages.any { it.id == msg.id } }))

    if (newerMessages.isNotEmpty()) {
        updateMessages(newerMessages)
        refreshCachedSenderProfiles(newerMessages)
        lastLoadedNewerId = anchorId
    }

    if (conversationPipelineMode == ConversationPipelineMode.New) {
        if (isLatestLoaded) {
            conversationSession.markBoundaryReached(loadingGeneration, HistoryDirection.Newer)
        }
    } else {
        _state.update { it.copy(isLatestLoaded = isLatestLoaded) }
    }
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
    val generation = loadingGeneration
    messageLoadingJob = scope.launch {
        ChatConversationLog.logViewportState(
            event = "scroll_to_message_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "messageId=$messageId"
        )
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            conversationSession.setOperationLoading(
                generation,
                loading = true,
                resetBoundaries = true
            )
        }
        _state.update {
            ConversationViewportReducer.beginLoad(
                state = it,
                legacyOwnsLoadingState = conversationPipelineMode != ConversationPipelineMode.New
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
            if (isActive && isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    conversationSession.setOperationLoading(generation, loading = false)
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }
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
    if (currentState.resolveScrollToBottomHandling() == ScrollToBottomHandling.Runtime) {
        _state.update { it.enqueueRuntimeScrollToBottom(animated = true) }
        ChatConversationLog.logViewportState(
            event = "scroll_to_bottom_runtime_enqueued",
            state = _state.value,
            componentInstanceId = componentInstanceId
        )
        if (!currentState.isLatestLoaded) {
            ChatConversationLog.logViewportState(
                event = "scroll_to_bottom_runtime_backfill_requested",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "isComments=$isComments"
            )
            ensureLatestWindowLoaded(maxPages = 6)
        }
        return
    }
    if (currentState.isLoading) return
    cancelAllLoadingJobs()
    val generation = loadingGeneration
    messageLoadingJob = scope.launch {
        ChatConversationLog.logViewportState(
            event = "scroll_to_bottom_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "isComments=$isComments"
        )
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            conversationSession.setOperationLoading(
                generation,
                loading = true,
                resetBoundaries = true
            )
        }
        _state.update {
            ConversationViewportReducer.beginLoad(
                state = it,
                legacyOwnsLoadingState = conversationPipelineMode != ConversationPipelineMode.New
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
            if (isActive && isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    conversationSession.setOperationLoading(generation, loading = false)
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }
            ChatConversationLog.logViewportState(
                event = "scroll_to_bottom_reload_finish",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
        }
    }
}

internal fun DefaultChatComponent.cancelAllLoadingJobs() {
    loadingGeneration += 1L
    if (conversationPipelineMode != ConversationPipelineMode.Legacy) {
        conversationSession.advanceGeneration(loadingGeneration)
    }
    messageLoadingJob?.cancel()
    loadMoreJob?.cancel()
    loadNewerJob?.cancel()
    inFlightOlderAnchorId = 0L
    inFlightNewerAnchorId = 0L
}

private fun DefaultChatComponent.ensureLatestWindowLoaded(maxPages: Int) {
    val currentState = _state.value
    if (currentState.isLoading || currentState.isLoadingNewer || currentState.isLatestLoaded) return
    if (loadNewerJob?.isActive == true) return

    loadNewerJob = scope.launch {
        if (conversationPipelineMode != ConversationPipelineMode.New) {
            _state.update { it.copy(isLoadingNewer = true) }
        }
        try {
            backfillNewerMessages(maxPages = maxPages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to backfill latest window", e)
            lastLoadedNewerId = 0L
        } finally {
            inFlightNewerAnchorId = 0L
            if (conversationPipelineMode != ConversationPipelineMode.New) {
                _state.update { it.copy(isLoadingNewer = false) }
            }
        }
    }
}

internal fun DefaultChatComponent.jumpToLatestInternal() {
    val currentState = _state.value
    val threadId = currentState.effectiveThreadId()
    val isComments = currentState.rootMessage != null
    val targetChatId = currentState.effectiveThreadChatId(chatId)
    ChatConversationLog.logViewportState(
        event = "jump_to_latest_requested",
        state = currentState,
        componentInstanceId = componentInstanceId,
        extra = "isComments=$isComments"
    )

    overrideViewportToBottomNow(threadId)

    if (currentState.isLoading) return
    cancelAllLoadingJobs()
    val generation = loadingGeneration
    messageLoadingJob = scope.launch {
        ChatConversationLog.logViewportState(
            event = "jump_to_latest_reset_before",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "isComments=$isComments"
        )
        if (conversationPipelineMode == ConversationPipelineMode.New) {
            conversationSession.setOperationLoading(
                generation,
                loading = true,
                resetBoundaries = true
            )
        }
        _state.update {
            ConversationViewportReducer.beginLoad(
                state = it.withResetSavedBottomViewport(),
                legacyOwnsLoadingState = conversationPipelineMode != ConversationPipelineMode.New
            )
        }
        ChatConversationLog.logViewportState(
            event = "jump_to_latest_reload_start",
            state = _state.value,
            componentInstanceId = componentInstanceId,
            extra = "isComments=$isComments"
        )
        try {
            check(isLoadingGenerationCurrent(generation)) { "Stale chat load generation" }
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
                event = "jump_to_latest_reload_failed",
                state = _state.value,
                componentInstanceId = componentInstanceId,
                extra = "error=${e.javaClass.simpleName}"
            )
            Log.e("DefaultChatComponent", "Failed to jump to latest", e)
        } finally {
            if (isLoadingGenerationCurrent(generation)) {
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    conversationSession.setOperationLoading(generation, loading = false)
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }
            ChatConversationLog.logViewportState(
                event = "jump_to_latest_reload_finish",
                state = _state.value,
                componentInstanceId = componentInstanceId
            )
        }
    }
}

internal fun DefaultChatComponent.setupMessageCollectors() {
    if (conversationPipelineMode == ConversationPipelineMode.New) {
        conversationSession.state
            .onEach { sessionState ->
                if (sessionState.closed) return@onEach
                _state.update { current ->
                    current.copy(
                        isLoading = sessionState.isLoadingInitial,
                        isLoadingOlder = sessionState.isLoadingOlder,
                        isLoadingNewer = sessionState.isLoadingNewer,
                        isOldestLoaded = sessionState.isOldestLoaded,
                        isLatestLoaded = sessionState.isLatestLoaded
                    )
                }
            }
            .launchIn(scope)
    }
    if (conversationPipelineMode != ConversationPipelineMode.Legacy) {
        repositoryMessage.conversationUpdates
            .onEach { update ->
                val state = _state.value
                val activeChatId = state.effectiveThreadChatId(chatId)
                if (update.chatId != activeChatId) return@onEach
                val scopedUpdate = when (update) {
                    is ConversationUpdate.Upsert -> state.isMessageInActiveThread(
                        chatId,
                        update.message
                    )

                    is ConversationUpdate.ReplaceTemporaryId ->
                        state.isMessageInActiveThread(chatId, update.message)

                    is ConversationUpdate.SendFailed ->
                        state.isMessageInActiveThread(chatId, update.message)

                    is ConversationUpdate.Delete,
                    is ConversationUpdate.InboxRead,
                    is ConversationUpdate.OutboxRead,
                    is ConversationUpdate.SendAcknowledged -> true
                }
                if (conversationPipelineMode == ConversationPipelineMode.New) {
                    applyConversationUpdateBookkeeping(
                        update = update,
                        remappedMessageIds = remappedMessageIds,
                        reactionUpdateSuppressedUntil = reactionUpdateSuppressedUntil
                    )
                }
                if (!scopedUpdate) return@onEach

                val sessionState = conversationSession.applyUpdate(
                    update = update,
                    canInsertNewMessage = state.isAtBottom || state.isLatestLoaded || when (update) {
                        is ConversationUpdate.Upsert -> update.isNew && update.message.isOutgoing
                        is ConversationUpdate.ReplaceTemporaryId -> update.message.isOutgoing
                        else -> false
                    }
                )
                if (conversationPipelineMode == ConversationPipelineMode.New && !sessionState.closed) {
                    val suppressReactionUpdate = (update as? ConversationUpdate.Upsert)
                        ?.takeUnless(ConversationUpdate.Upsert::isNew)
                        ?.message
                        ?.id
                        ?.let { messageId ->
                            val suppressUntil = reactionUpdateSuppressedUntil[messageId]
                            when {
                                suppressUntil == null -> false
                                System.currentTimeMillis() < suppressUntil -> true
                                else -> {
                                    reactionUpdateSuppressedUntil.remove(messageId, suppressUntil)
                                    false
                                }
                            }
                        } == true
                    _state.update { current ->
                        current.withConversationSessionUpdate(
                            sessionState = sessionState,
                            update = update,
                            rootChatId = chatId,
                            suppressReactionUpdate = suppressReactionUpdate
                        )
                    }
                    when (update) {
                        is ConversationUpdate.Upsert -> {
                            if (update.isNew) requestSenderRefreshIfNeeded(update.message)
                            if (!update.isNew) handleEditedRichMessage(update.message)
                        }

                        is ConversationUpdate.ReplaceTemporaryId ->
                            requestSenderRefreshIfNeeded(update.message)

                        is ConversationUpdate.Delete,
                        is ConversationUpdate.InboxRead,
                        is ConversationUpdate.OutboxRead,
                        is ConversationUpdate.SendAcknowledged,
                        is ConversationUpdate.SendFailed -> Unit
                    }
                }
            }
            .launchIn(scope)
    }

    repositoryMessage.newMessageFlow
        .onEach { message ->
            if (message.chatId == chatId || message.chatId == activeThreadChatId()) {
                if (resolveRemappedMessageId(message.id) != message.id) return@onEach
                val isCorrectThread = _state.value.isMessageInActiveThread(chatId, message)
                if (isCorrectThread) {
                    if (conversationPipelineMode != ConversationPipelineMode.New) {
                        _state.update { state ->
                            state.withIncomingUnreadMessage(
                                rootChatId = chatId,
                                message = message
                            )
                        }
                        updateMessages(listOf(message))
                    }
                    if (conversationPipelineMode != ConversationPipelineMode.New) {
                        requestSenderRefreshIfNeeded(message)
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
                if (conversationPipelineMode != ConversationPipelineMode.New) {
                    if (oldId != newMessage.id) {
                        remappedMessageIds[oldId] = newMessage.id
                    } else {
                        remappedMessageIds.remove(oldId)
                    }
                }
                if (conversationPipelineMode != ConversationPipelineMode.New) messageMutex.withLock {
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
                            outgoingMessageStates = OutgoingMessageReducer.succeeded(
                                current = state.outgoingMessageStates,
                                key = OutgoingMessageReducer.Key(cId, oldId),
                                finalMessageId = newMessage.id
                            ),
                            isLatestLoaded = if (newMessage.isOutgoing || state.isAtBottom) true else state.isLatestLoaded
                        )
                    }
                }

                val isCurrentThread = _state.value.isMessageInActiveThread(chatId, newMessage)
                if (isCurrentThread && conversationPipelineMode != ConversationPipelineMode.New) {
                    requestSenderRefreshIfNeeded(newMessage)
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageAcknowledgedFlow
        .onEach { event ->
            if (event.chatId != chatId && event.chatId != activeThreadChatId()) return@onEach
            if (conversationPipelineMode != ConversationPipelineMode.New) _state.update { state ->
                state.copy(
                    outgoingMessageStates = OutgoingMessageReducer.acknowledged(
                        current = state.outgoingMessageStates,
                        key = OutgoingMessageReducer.Key(event.chatId, event.temporaryMessageId)
                    )
                )
            }
        }
        .launchIn(scope)

    repositoryMessage.messageSendFailedFlow
        .onEach { event ->
            if (event.chatId != chatId && event.chatId != activeThreadChatId()) return@onEach
            if (conversationPipelineMode != ConversationPipelineMode.New) _state.update { state ->
                state.copy(
                    outgoingMessageStates = OutgoingMessageReducer.failed(
                        current = state.outgoingMessageStates,
                        key = OutgoingMessageReducer.Key(event.chatId, event.temporaryMessageId),
                        errorCode = event.errorCode
                    )
                )
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
                        message.copy(
                            content = message.content.withFileDownloadState(
                                fileId = event.fileId,
                                isDownloading = isDownloading,
                                progress = event.progress
                            )
                        )
                    }
                }

                is MessageDownloadEvent.Cancelled -> {
                    if (event.chatId != chatId) return@onEach
                    updateMessageContent(event.messageId) { message ->
                        message.copy(
                            content = message.content.withFileDownloadState(
                                fileId = event.fileId,
                                isDownloading = false,
                                progress = 0f
                            )
                        )
                    }
                    AutoDownloadSuppression.suppress(event.fileId)
                }

                is MessageDownloadEvent.Completed -> {
                    if (event.chatId != chatId) return@onEach
                    val messageId = event.messageId
                    val downloadedFileId = event.fileId
                    val path = event.path
                    var mainFileId = 0
                    var mainPathUpdated = false

                    updateMessageContent(messageId) { message ->
                        val isError = path.isEmpty()
                        val finalPath = path.ifEmpty { null }

                        val newContent = when (val content = message.content) {
                            is MessageContent.Photo -> {
                                val isOriginalFile = content.originalFileId != 0 &&
                                        downloadedFileId == content.originalFileId &&
                                        downloadedFileId != content.fileId
                                if (isOriginalFile) {
                                    content.copy(
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else if (downloadedFileId == content.fileId) {
                                    mainFileId = downloadedFileId
                                    mainPathUpdated = true
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
                                    content.copy(
                                        path = finalPath,
                                        isDownloading = false,
                                        downloadError = isError
                                    )
                                } else {
                                    content
                                }
                            }

                            is MessageContent.Audio -> {
                                if (downloadedFileId == content.fileId) {
                                    mainFileId = content.fileId
                                    mainPathUpdated = true
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
                    }

                    if (path.isNotEmpty() && messageId in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        updateInlineResultsWithFile(messageId.toInt(), path)
                    }

                    if (path.isNotEmpty() && messageId == downloadedFileId.toLong()) {
                        refreshCachedSenderProfiles(_state.value.messages)
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
                if (conversationPipelineMode != ConversationPipelineMode.New) {
                    messageIds.forEach(reactionUpdateSuppressedUntil::remove)
                    messageIds.forEach(remappedMessageIds::remove)
                    remappedMessageIds.entries.removeIf { (_, mappedId) -> mappedId in messageIds }
                }
                if (conversationPipelineMode != ConversationPipelineMode.New) _state.update { currentState ->
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
                if (conversationPipelineMode != ConversationPipelineMode.New) messageMutex.withLock {
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
                if (conversationPipelineMode != ConversationPipelineMode.New) {
                    handleEditedRichMessage(message)
                }
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
                if (conversationPipelineMode != ConversationPipelineMode.New) _state.update { currentState ->
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

private suspend inline fun DefaultChatComponent.updateMessageContent(
    messageId: Long,
    noinline transform: (MessageModel) -> MessageModel
) {
    val targetMessageId = resolveRemappedMessageId(messageId)
    if (conversationPipelineMode == ConversationPipelineMode.New) {
        val sessionState = conversationSession.transformMessage(
            chatId = activeThreadChatId(),
            messageId = targetMessageId,
            transform = transform
        )
        if (!sessionState.closed) {
            _state.update { currentState ->
                if (currentState.messages == sessionState.messages) currentState
                else currentState.copy(messages = sessionState.messages)
            }
        }
    } else {
        messageMutex.withLock {
            _state.update { currentState ->
                currentState.withUpdatedMessage(
                    targetMessageId,
                    transform
                )
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
        ConversationViewportReducer.initialize(
            it.copy(
            currentTopicId = id,
            currentThreadChatId = null,
            currentMessageThreadId = null,
            messages = emptyList(),
            isOldestLoaded = false,
            isLatestLoaded = false,
            rootMessage = null,
                isAtBottom = id == null
            )
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
            ConversationViewportReducer.initialize(
                it.copy(
                currentTopicId = messageId,
                currentThreadChatId = threadContext?.chatId,
                currentMessageThreadId = threadContext?.threadId ?: messageId,
                rootMessage = message,
                messages = emptyList(),
                isOldestLoaded = false,
                isLatestLoaded = false,
                    isAtBottom = false
                )
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
