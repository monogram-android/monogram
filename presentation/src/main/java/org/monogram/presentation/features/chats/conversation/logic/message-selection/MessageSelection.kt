package org.monogram.presentation.features.chats.conversation.logic

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent


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
            val targetChatId = activeThreadChatId()
            if (true) {
                val chat = requireNotNull(chatListRepository.getChatById(targetChatId)) {
                    "MTProto target chat is not projected"
                }
                val peer = TelegramPeerChatId.decode(targetChatId, chat.isChannel)
                mtProtoMessageDeletionRepository.delete(targetChatId, peer.type, ids, revoke)
            } else {
                repositoryMessage.deleteMessage(targetChatId, ids, revoke)
            }
            onClearSelection()
        }
    }
}
