package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ReadUpdate
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent
import org.monogram.presentation.features.chats.currentChat.DownloadDebug


private const val PAGE_SIZE = 50

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
            val previous = messageMap.put(msg.id, msg)
            if (previous != msg) {
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
    updateMessages(messages, replace = !hasCachedPreview)
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
    if (loadMoreJob?.isActive == true || state.isLoadingOlder || (state.isOldestLoaded && !forceLoad)) return

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
            _state.update { it.copy(isLoadingOlder = false) }
        }
    }
}

internal fun DefaultChatComponent.loadNewerMessages() {
    val state = _state.value
    if (loadNewerJob?.isActive == true || state.isLoadingNewer || state.isLatestLoaded) return

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
}

internal fun DefaultChatComponent.setupMessageCollectors() {
    repositoryMessage.newMessageFlow
        .onEach { message ->
            if (message.chatId == chatId) {
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
                messageMutex.withLock {
                    _state.update { state ->
                        val currentMessages = state.messages.toMutableList()
                        val index = currentMessages.indexOfFirst { it.id == oldId }

                        if (index != -1) {
                            currentMessages[index] = newMessage
                        } else {
                            val isCorrectThread =
                                state.currentTopicId == null || newMessage.threadId?.toLong() == state.currentTopicId
                            if (isCorrectThread && (state.isAtBottom || state.isLatestLoaded || newMessage.isOutgoing)) {
                                if (currentMessages.none { it.id == newMessage.id }) {
                                    currentMessages.add(newMessage)
                                }
                            }
                        }

                        val isComments = state.rootMessage != null
                        val distinctMessages = currentMessages.distinctBy { it.id }
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
            Log.d(DownloadDebug.TAG, "downloadCancelled: messageId=$messageId fileId=$cancelledFileId chatId=$chatId")
        }
        .launchIn(scope)

    repositoryMessage.messageDownloadCompletedFlow
        .onEach { (messageId, path) ->
            var fileIdToRetry: Int? = null
            var completedFileId = 0

            updateMessageContent(messageId) { message ->
                val isError = path.isEmpty()
                val finalPath = path.ifEmpty { null }

                val newContent = when (val content = message.content) {
                    is MessageContent.Photo -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    is MessageContent.Video -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    is MessageContent.VideoNote -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    is MessageContent.Document -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    is MessageContent.Gif -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    is MessageContent.Voice -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    is MessageContent.Sticker -> {
                        completedFileId = content.fileId
                        if (isError) fileIdToRetry = content.fileId
                        content.copy(path = finalPath, isDownloading = false, downloadError = isError)
                    }

                    else -> content
                }
                message.copy(content = newContent)
            }

            if (path.isNotEmpty()) {
                AutoDownloadSuppression.clear(completedFileId)
            }

            fileIdToRetry?.let {
                if (it != 0) {
                    val suppressed = AutoDownloadSuppression.isSuppressed(it)
                    Log.d(
                        DownloadDebug.TAG,
                        "downloadCompletedError: messageId=$messageId fileId=$it suppressed=$suppressed chatId=$chatId"
                    )
                    if (!suppressed) {
                        onDownloadFile(it)
                    } else {
                        Log.d(DownloadDebug.TAG, "retrySkippedBySuppression: fileId=$it chatId=$chatId")
                    }
                }
            }
        }
        .launchIn(scope)

    repositoryMessage.messageDeletedFlow
        .onEach { (cId, messageIds) ->
            if (cId == chatId) {
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
                updateMessageContent(message.id) { message }
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
                message.copy(
                    senderName = fullName,
                    senderAvatar = user.avatarPath ?: message.senderAvatar,
                    senderPersonalAvatar = user.personalAvatarPath ?: message.senderPersonalAvatar,
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

private inline fun DefaultChatComponent.updateMessageContent(
    messageId: Long,
    crossinline transform: (MessageModel) -> MessageModel
) {
    scope.launch {
        messageMutex.withLock {
            _state.update { currentState ->
                val currentMessages = currentState.messages.toMutableList()
                val index = currentMessages.indexOfFirst { it.id == messageId }
                if (index != -1) {
                    currentMessages[index] = transform(currentMessages[index])
                    currentState.copy(messages = currentMessages)
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
