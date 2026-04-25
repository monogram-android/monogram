package org.monogram.presentation.features.chats.conversation.logic

import android.util.Log
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent


internal fun DefaultChatComponent.loadPinnedMessage() {
    scope.launch {
        val threadId = activeThreadId()
        val targetChatId = activeThreadChatId()
        try {
            val pinned = repositoryMessage.getPinnedMessage(targetChatId, threadId)
            val count = repositoryMessage.getPinnedMessageCount(targetChatId, threadId)
            Log.d(
                "DefaultChatComponent",
                "loadPinnedMessage: chatId=$chatId, targetChatId=$targetChatId, threadId=$threadId, pinnedId=${pinned?.id}, count=$count"
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
        val threadId = activeThreadId()
        val targetChatId = activeThreadChatId()
        _state.update { it.copy(isLoadingPinnedMessages = true) }
        try {
            val pinnedMessages = repositoryMessage.getAllPinnedMessages(targetChatId, threadId)
            _state.update {
                it.copy(
                    allPinnedMessages = pinnedMessages,
                    isLoadingPinnedMessages = false
                )
            }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Error loading all pinned messages", e)
            _state.update { it.copy(isLoadingPinnedMessages = false) }
        }
    }
}

internal fun DefaultChatComponent.loadScheduledMessages() {
    scope.launch {
        if (!canLoadScheduledMessages()) {
            _state.update { it.copy(scheduledMessages = emptyList()) }
            return@launch
        }

        try {
            val scheduledMessages = repositoryMessage.getScheduledMessages(chatId)
            _state.update { it.copy(scheduledMessages = scheduledMessages) }
        } catch (e: Exception) {
            Log.e("DefaultChatComponent", "Error loading scheduled messages", e)
        }
    }
}

private suspend fun DefaultChatComponent.canLoadScheduledMessages(): Boolean {
    val currentState = _state.value
    if (currentState.isChannel && !currentState.isAdmin) return false
    if (currentState.canWrite) return true

    val chat = chatListRepository.getChatById(chatId) ?: return false
    val canWrite = if (chat.isAdmin) true else chat.permissions.canSendBasicMessages
    if (chat.isChannel && !chat.isAdmin) return false

    return canWrite
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
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        val nextIndex = (currentState.pinnedMessageIndex + 1) % currentState.pinnedMessageCount
        val pinnedMessages = repositoryMessage.getAllPinnedMessages(targetChatId, threadId)
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
    scrollToMessage(message.id)
}
