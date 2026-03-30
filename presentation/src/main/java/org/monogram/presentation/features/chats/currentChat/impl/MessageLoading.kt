package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import org.monogram.domain.models.*
import org.monogram.domain.repository.ReadUpdate
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent
import java.io.File


private const val PAGE_SIZE = 50
private const val MAX_DOWNLOAD_RETRIES = 3

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
        senderStatusEmojiPath = incoming.senderStatusEmojiPath ?: previous.senderStatusEmojiPath
    )
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
                previous.customEmojiPath == reaction.customEmojiPath
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

        val isComments = state.rootMessage != null

        val messageMap = LinkedHashMap<Long, MessageModel>(currentList.size + filteredNewMessages.size)
        currentList.forEach { messageMap[it.id] = it }

        var hasChanges = replace
        filteredNewMessages.forEach { msg ->
            val previous = messageMap[msg.id]
            val mergedMessage = if (previous != null) mergeSenderVisuals(previous, msg) else msg
            val old = messageMap.put(msg.id, mergedMessage)
            if (old != mergedMessage) {
                hasChanges = true
            }
        }

        if (!hasChanges) {
            return@update state
        }

        val mergedMessages = messageMap.values.let {
            if (isComments) {
                it.sortedWith(compareBy<MessageModel> { it.date }.thenBy { it.id })
            } else {
                it.sortedWith(compareByDescending<MessageModel> { it.date }.thenByDescending { it.id })
            }
        }

        if (mergedMessages == state.messages) state else state.copy(messages = mergedMessages)
    }
}

internal suspend fun DefaultChatComponent.updateMessages(newMessages: List<MessageModel>, replace: Boolean = false) {
    messageMutex.withLock {
        updateMessagesUnsafe(newMessages, replace)
    }
}

internal fun DefaultChatComponent.loadMessages(force: Boolean = false) {
    val state = _state.value
    if (state.isLoading) return
    if (!force && state.messages.size >= PAGE_SIZE && state.currentTopicId == null) return

    cancelAllLoadingJobs()
    messageLoadingJob = scope.launch {
        _state.update {
            it.copy(
                isLoading = true,
                isOldestLoaded = false,
                isLatestLoaded = false
            )
        }

        try {
            val currentState = _state.value
            val threadId = currentState.currentTopicId
            val isComments = currentState.rootMessage != null
            val savedScrollPosition = if (threadId == null) cacheProvider.getChatScrollPosition(chatId) else 0L

            if (isComments && threadId != null) {
                loadComments(threadId)
            } else if (savedScrollPosition != 0L) {
                loadAroundMessage(savedScrollPosition, threadId, shouldHighlight = false)
            } else {
                val chat = chatsListRepository.getChatById(chatId)
                val firstUnreadId = chat?.lastReadInboxMessageId?.let { lastRead ->
                    if (chat.unreadCount > 0) {
                        repositoryMessage.getMessagesNewer(chatId, lastRead, 1, threadId).firstOrNull()?.id
                            ?: lastRead.takeIf { it > 0L }
                    } else null
                }

                if (firstUnreadId != null) {
                    loadAroundMessage(firstUnreadId, threadId, shouldHighlight = false)
                } else {
                    loadBottomMessages(threadId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load messages", e)
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }
}

internal suspend fun DefaultChatComponent.loadComments(threadId: Long) {
    lastLoadedOlderId = 0L
    lastLoadedNewerId = 0L
    val messages = repositoryMessage.getMessagesNewer(chatId, threadId, PAGE_SIZE, threadId)
    val reachedEnd = messages.size < PAGE_SIZE

    _state.update {
        it.copy(
            isAtBottom = false,
            isLatestLoaded = reachedEnd,
            isOldestLoaded = true,
            scrollToMessageId = messages.firstOrNull()?.id
        )
    }
    updateMessages(messages, replace = true)
}

private suspend fun DefaultChatComponent.loadBottomMessages(threadId: Long?) {
    lastLoadedOlderId = 0L
    lastLoadedNewerId = 0L

    var hasCachedPreview = false
    val cachedMessages = repositoryMessage.getCachedMessages(chatId, PAGE_SIZE)
    if (cachedMessages.isNotEmpty()) {
        hasCachedPreview = true
        _state.update {
            it.copy(
                isAtBottom = true,
                isLatestLoaded = false,
                isOldestLoaded = false,
                scrollToMessageId = null
            )
        }
        updateMessages(cachedMessages, replace = true)
        refreshCachedSenderProfiles(cachedMessages)
    }

    val olderPage = repositoryMessage.getMessagesOlder(chatId, 0, PAGE_SIZE, threadId)
    val messages = olderPage.messages
    val isRemoteSameAsCachedPreview = hasCachedPreview && cachedMessages.isNotEmpty() &&
            messages.size == cachedMessages.size &&
            messages.zip(cachedMessages).all { (remote, cached) -> remote.id == cached.id }

    val isOldestLoaded = if (isRemoteSameAsCachedPreview) {
        false
    } else {
        olderPage.reachedOldest
    }

    _state.update {
        it.copy(
            isAtBottom = true,
            isLatestLoaded = !isRemoteSameAsCachedPreview,
            isOldestLoaded = isOldestLoaded,
            scrollToMessageId = null
        )
    }
    val shouldReplaceCachedPreview = !hasCachedPreview || messages.isNotEmpty()
    updateMessages(messages, replace = shouldReplaceCachedPreview)
    if (!isOldestLoaded) {
        delay(100)
        loadMoreMessages()
    }
}

private suspend fun DefaultChatComponent.loadAroundMessage(
    messageId: Long,
    threadId: Long?,
    shouldHighlight: Boolean = true
) {
    lastLoadedOlderId = 0L
    lastLoadedNewerId = 0L
    val messages = repositoryMessage.getMessagesAround(chatId, messageId, PAGE_SIZE, threadId)
    if (messages.isNotEmpty()) {
        _state.update {
            it.copy(
                isAtBottom = false,
                isLatestLoaded = false,
                isOldestLoaded = false,
                scrollToMessageId = messageId,
                highlightedMessageId = if (shouldHighlight) messageId else null
            )
        }
        updateMessages(messages, replace = true)
        delay(100)
        loadMoreMessages()
        loadNewerMessages()
    } else {
        loadBottomMessages(threadId)
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
            val threadId = currentState.currentTopicId

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
                val olderPage = repositoryMessage.getMessagesOlder(chatId, currentAnchorId, PAGE_SIZE, threadId)
                val olderMessages = olderPage.messages

                val nextOlderAnchorId = olderMessages
                    .asSequence()
                    .map { it.id }
                    .filter { it in 1 until currentAnchorId }
                    .minOrNull() ?: currentAnchorId

                val hasOlderProgress = nextOlderAnchorId < currentAnchorId

                if (olderMessages.isNotEmpty()) {
                    updateMessages(olderMessages)
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
            val currentState = _state.value
            val currentMessages = currentState.messages
            val isComments = currentState.rootMessage != null
            val threadId = currentState.currentTopicId

            val anchorId = if (isComments) {
                currentMessages.lastOrNull { it.id > 0 }?.id ?: return@launch
            } else {
                currentMessages.firstOrNull { it.id > 0 }?.id ?: return@launch
            }

            inFlightNewerAnchorId = anchorId

            if (anchorId != 0L && anchorId == lastLoadedNewerId) {
                _state.update { it.copy(isLatestLoaded = true) }
                return@launch
            }

            val newerMessages = repositoryMessage.getMessagesNewer(chatId, anchorId, PAGE_SIZE, threadId)
            val isLatestLoaded =
                newerMessages.size < PAGE_SIZE || (newerMessages.isNotEmpty() && newerMessages.all { msg -> currentMessages.any { it.id == msg.id } })

            if (newerMessages.isNotEmpty()) {
                updateMessages(newerMessages)
                lastLoadedNewerId = anchorId
            }

            _state.update { it.copy(isLatestLoaded = isLatestLoaded) }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to load newer messages", e)
            lastLoadedNewerId = 0L
        } finally {
            inFlightNewerAnchorId = 0L
            _state.update { it.copy(isLoadingNewer = false) }
        }
    }
}

internal fun DefaultChatComponent.scrollToMessageInternal(messageId: Long) {
    cancelAllLoadingJobs()
    messageLoadingJob = scope.launch {
        _state.update {
            it.copy(
                isLoading = true,
                isOldestLoaded = false,
                isLatestLoaded = false
            )
        }
        try {
            loadAroundMessage(messageId, _state.value.currentTopicId, shouldHighlight = true)
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to scroll to message", e)
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }
}

internal fun DefaultChatComponent.scrollToBottomInternal() {
    if (_state.value.isLoading) return
    cancelAllLoadingJobs()
    messageLoadingJob = scope.launch {
        _state.update {
            it.copy(
                isLoading = true,
                isOldestLoaded = false,
                isLatestLoaded = false
            )
        }
        try {
            loadBottomMessages(_state.value.currentTopicId)
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Failed to scroll to bottom", e)
        } finally {
            _state.update { it.copy(isLoading = false) }
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
            if (message.chatId == chatId) {
                if (resolveRemappedMessageId(message.id) != message.id) {
                    return@onEach
                }
                val isCorrectThread =
                    _state.value.currentTopicId == null || message.threadId?.toLong() == _state.value.currentTopicId
                if (isCorrectThread) {
                    updateMessages(listOf(message))
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
        .onEach { (cId, oldId, newMessage) ->
            if (cId == chatId) {
                if (oldId != newMessage.id) {
                    remappedMessageIds[oldId] = newMessage.id
                } else {
                    remappedMessageIds.remove(oldId)
                }
                messageMutex.withLock {
                    _state.update { state ->
                        val isCorrectThread =
                            state.currentTopicId == null || newMessage.threadId?.toLong() == state.currentTopicId
                        if (!isCorrectThread) {
                            return@update state
                        }

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
            }
        }
        .launchIn(scope)

    repositoryMessage.messageUploadProgressFlow
        .onEach { (messageId, progress) ->
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

    repositoryMessage.messageDownloadProgressFlow
        .onEach { (messageId, progress) ->
            updateMessageContent(messageId) { message ->
                val isDownloading = progress < 1f
                val newContent = when (val content = message.content) {
                    is MessageContent.Photo -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    is MessageContent.Video -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    is MessageContent.VideoNote -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    is MessageContent.Document -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    is MessageContent.Gif -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    is MessageContent.Voice -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    is MessageContent.Sticker -> content.copy(
                        isDownloading = isDownloading,
                        downloadProgress = progress,
                        downloadError = false
                    )

                    else -> content
                }
                message.copy(content = newContent)
            }
        }
        .launchIn(scope)

    repositoryMessage.messageDownloadCancelledFlow
        .onEach { messageId ->
            var cancelledFileId = 0
            updateMessageContent(messageId) { message ->
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
        .launchIn(scope)

    repositoryMessage.messageDownloadCompletedFlow
        .onEach { (messageId, downloadedFileId, path) ->
            var fileIdToRetry: Int? = null
            var mainFileId = 0
            var mainPathUpdated = false

            updateMessageContent(messageId) { message ->
                val isError = path.isEmpty()
                val finalPath = path.ifEmpty { null }

                val newContent = when (val content = message.content) {
                    is MessageContent.Photo -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                        } else {
                            if (finalPath != null) content.copy(thumbnailPath = finalPath) else content
                        }
                    }

                    is MessageContent.Video -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                        } else {
                            if (finalPath != null) content.copy(thumbnailPath = finalPath) else content
                        }
                    }

                    is MessageContent.VideoNote -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                        } else {
                            content
                        }
                    }

                    is MessageContent.Document -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                        } else {
                            content
                        }
                    }

                    is MessageContent.Gif -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                        } else {
                            content
                        }
                    }

                    is MessageContent.Voice -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                        } else {
                            content
                        }
                    }

                    is MessageContent.Sticker -> {
                        if (downloadedFileId == content.fileId) {
                            mainFileId = content.fileId
                            mainPathUpdated = true
                            if (isError) fileIdToRetry = content.fileId
                            content.copy(path = finalPath, isDownloading = false, downloadError = isError)
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
                        Log.d("DownloadDebug", "retrySkippedBySuppression: fileId=$it chatId=$chatId")
                    }
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageDeletedFlow
        .onEach { (cId, messageIds) ->
            if (cId == chatId) {
                messageIds.forEach(reactionUpdateSuppressedUntil::remove)
                messageIds.forEach(remappedMessageIds::remove)
                remappedMessageIds.entries.removeIf { (_, mappedId) -> mappedId in messageIds }
                _state.update { currentState ->
                    val currentMessages = currentState.messages.toMutableList()
                    val removed = currentMessages.removeAll { messageIds.contains(it.id) }
                    if (removed) {
                        currentState.copy(messages = currentMessages)
                    } else {
                        currentState
                    }
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageEditedFlow
        .onEach { message ->
            if (message.chatId == chatId) {
                val now = System.currentTimeMillis()
                val suppressUntil = reactionUpdateSuppressedUntil[message.id]
                val suppressReactionUpdate = suppressUntil != null && now < suppressUntil

                if (!suppressReactionUpdate && suppressUntil != null) {
                    reactionUpdateSuppressedUntil.remove(message.id, suppressUntil)
                }

                updateMessageContent(message.id) { current ->
                    val mediaSafeMessage = when {
                        current.content is MessageContent.Photo && message.content is MessageContent.Photo -> {
                            val currentPhoto = current.content as MessageContent.Photo
                            val incomingPhoto = message.content as MessageContent.Photo
                            if (currentPhoto.fileId == incomingPhoto.fileId) {
                                val resolvedPath = incomingPhoto.path ?: currentPhoto.path
                                message.copy(
                                    content = incomingPhoto.copy(
                                        path = resolvedPath,
                                        thumbnailPath = incomingPhoto.thumbnailPath ?: currentPhoto.thumbnailPath
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
                                val resolvedPath = incomingVideo.path ?: currentVideo.path
                                message.copy(
                                    content = incomingVideo.copy(
                                        path = resolvedPath,
                                        thumbnailPath = incomingVideo.thumbnailPath ?: currentVideo.thumbnailPath
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
            if (readUpdate.chatId == chatId) {
                _state.update { currentState ->
                    val currentMessages = currentState.messages
                    var hasChanges = false
                    val updatedMessages = currentMessages.map { message ->
                        if (readUpdate is ReadUpdate.Outbox && message.isOutgoing && !message.isRead && message.id <= readUpdate.messageId) {
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

private suspend fun DefaultChatComponent.refreshCachedSenderProfiles(messages: List<MessageModel>) {
    val senderIds = messages.asSequence()
        .map { it.senderId }
        .filter { it > 0L }
        .distinct()
        .toList()

    senderIds.forEach { senderId ->
        repositoryMessage.invalidateSenderCache(senderId)
        val user = userRepository.getUser(senderId) ?: return@forEach
        refreshMessagesForSender(senderId, user)
    }
}

private fun DefaultChatComponent.refreshMessagesForSender(senderId: Long, user: UserModel) {
    val fullName = listOfNotNull(
        user.firstName.takeIf { it.isNotBlank() },
        user.lastName?.takeIf { it.isNotBlank() }
    ).joinToString(" ").ifBlank { "User" }

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
    crossinline transform: (MessageModel) -> MessageModel
) {
    scope.launch {
        messageMutex.withLock {
            val targetMessageId = resolveRemappedMessageId(messageId)
            _state.update { currentState ->
                val currentMessages = currentState.messages.toMutableList()
                val index = currentMessages.indexOfFirst { it.id == targetMessageId }
                if (index != -1) {
                    val currentMessage = currentMessages[index]
                    val updatedMessage = transform(currentMessage)
                    if (updatedMessage != currentMessage) {
                        currentMessages[index] = updatedMessage
                        currentState.copy(messages = currentMessages)
                    } else {
                        currentState
                    }
                } else {
                    currentState
                }
            }
        }
    }
}

internal fun DefaultChatComponent.loadDraft() {
    scope.launch {
        val threadId = _state.value.currentTopicId
        val draft = repositoryMessage.getChatDraft(chatId, threadId)
        if (!draft.isNullOrEmpty()) {
            _state.update { it.copy(draftText = draft) }
        }
    }
}

internal fun DefaultChatComponent.handleTopicClick(topicId: Int) {
    val id = if (topicId == 0) null else topicId.toLong()
    _state.update {
        it.copy(
            currentTopicId = id,
            messages = emptyList(),
            isOldestLoaded = false,
            isLatestLoaded = false,
            rootMessage = null,
            isAtBottom = id == null
        )
    }
    loadMessages(force = true)
}

internal fun DefaultChatComponent.handleCommentsClick(messageId: Long) {
    scope.launch {
        val message = _state.value.messages.find { it.id == messageId }
        _state.update {
            it.copy(
                currentTopicId = messageId,
                rootMessage = message,
                messages = emptyList(),
                isOldestLoaded = false,
                isLatestLoaded = false,
                isAtBottom = false
            )
        }
        loadComments(messageId)
    }
}
