package org.monogram.presentation.features.chats.currentChat.impl

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent


internal fun DefaultChatComponent.handleToggleMessageSelection(messageId: Long) {
    _state.update { currentState ->
        val current = currentState.selectedMessageIds
        if (current.contains(messageId)) {
            currentState.copy(selectedMessageIds = current - messageId)
        } else {
            if (current.size < 100) {
                currentState.copy(selectedMessageIds = current + messageId)
            } else {
                currentState
            }
        }
    }
}

internal fun DefaultChatComponent.handleClearSelection() {
    _state.update { it.copy(selectedMessageIds = emptySet()) }
}

internal fun DefaultChatComponent.handleDeleteSelectedMessages(revoke: Boolean = false) {
    val ids = _state.value.selectedMessageIds.toList().sorted()
    if (ids.isNotEmpty()) {
        scope.launch {
            repositoryMessage.deleteMessage(chatId, ids, revoke)
            onClearSelection()
        }
    }
}
