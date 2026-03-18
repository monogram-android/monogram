package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent


internal fun DefaultChatComponent.loadPinnedMessage() {
    scope.launch {
        val threadId = _state.value.currentTopicId
        try {
            val pinned = repositoryMessage.getPinnedMessage(chatId, threadId)
            val count = repositoryMessage.getPinnedMessageCount(chatId, threadId)
            Log.d(
                "DefaultChatComponent",
                "loadPinnedMessage: chatId=$chatId, threadId=$threadId, pinnedId=${pinned?.id}, count=$count"
            )
            _state.update {
                it.copy(
                    pinnedMessage = pinned,
                    pinnedMessageCount = count,
                    pinnedMessageIndex = 0
                )
            }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Error loading pinned message", e)
        }
    }
}

internal fun DefaultChatComponent.loadAllPinnedMessages() {
    scope.launch {
        val threadId = _state.value.currentTopicId
        try {
            val pinnedMessages = repositoryMessage.getAllPinnedMessages(chatId, threadId)
            _state.update {
                it.copy(allPinnedMessages = pinnedMessages)
            }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Error loading all pinned messages", e)
        }
    }
}

internal fun DefaultChatComponent.setupPinnedMessageCollector() {
    repositoryMessage.pinnedMessageFlow
        .onEach { cId ->
            if (cId == chatId) {
                Log.d("DefaultChatComponent", "pinnedMessageFlow triggered for chatId=$chatId")
                loadPinnedMessage()
                if (_state.value.showPinnedMessagesList) {
                    loadAllPinnedMessages()
                }
            }
        }
        .launchIn(scope)
}

internal fun DefaultChatComponent.handlePinnedMessageClick(message: MessageModel?) {
    if (message != null) {
        jumpToMessage(message)
        return
    }

    val currentState = _state.value
    if (currentState.pinnedMessageCount <= 1) {
        currentState.pinnedMessage?.let { jumpToMessage(it) }
        return
    }

    scope.launch {
        val threadId = currentState.currentTopicId
        val nextIndex = (currentState.pinnedMessageIndex + 1) % currentState.pinnedMessageCount
        val pinnedMessages = repositoryMessage.getAllPinnedMessages(chatId, threadId)
        if (pinnedMessages.isNotEmpty()) {
            val nextPinned = pinnedMessages.getOrNull(nextIndex) ?: pinnedMessages.first()
            _state.update {
                it.copy(
                    pinnedMessage = nextPinned,
                    pinnedMessageIndex = nextIndex
                )
            }
            jumpToMessage(nextPinned)
        }
    }
}

private fun DefaultChatComponent.jumpToMessage(message: MessageModel) {
    if (_state.value.isLoading) return

    scope.launch {
        _state.update { it.copy(isLoading = true) }
        try {
            val threadId = _state.value.currentTopicId
            val messages = repositoryMessage.getMessagesAround(chatId, message.id, 50, threadId)
            if (messages.isNotEmpty()) {
                _state.update {
                    it.copy(
                        scrollToMessageId = message.id,
                        highlightedMessageId = message.id,
                        isAtBottom = false,
                        isLatestLoaded = false,
                        isOldestLoaded = false
                    )
                }
                updateMessages(messages, replace = true)
                lastLoadedOlderId = 0L
                lastLoadedNewerId = 0L
                loadMoreMessages()
                loadNewerMessages()
            }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Error jumping to message", e)
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }
}
