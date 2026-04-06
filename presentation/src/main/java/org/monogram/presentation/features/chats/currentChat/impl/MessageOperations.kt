package org.monogram.presentation.features.chats.currentChat.impl

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageReactionModel
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

private const val REACTION_UPDATE_SUPPRESSION_MS = 1500L


internal fun DefaultChatComponent.handleMessageVisible(messageId: Long) {
    scope.launch {
        repositoryMessage.markAsRead(chatId, messageId)
        if (_state.value.unreadCount > 0) {
            repositoryMessage.markAllMentionsAsRead(chatId)
            repositoryMessage.markAllReactionsAsRead(chatId)
        }

        _state.value.messages
            .firstOrNull { it.id == messageId }
            ?.let { visibleMessage ->
                requestSenderRefreshIfNeeded(visibleMessage)
            }
    }
}

internal fun DefaultChatComponent.handleDeleteMessage(message: MessageModel, revoke: Boolean = false) {
    scope.launch {
        repositoryMessage.deleteMessage(chatId, listOf(message.id), revoke)
    }
}

internal fun DefaultChatComponent.handleSaveEditedMessage(text: String, entities: List<MessageEntity>) {
    val editingMsg = _state.value.editingMessage ?: return
    scope.launch {
        repositoryMessage.editMessage(chatId, editingMsg.id, text, entities)
        onCancelEdit()
    }
}

internal fun DefaultChatComponent.handleDraftChange(text: String) {
    _state.update { it.copy(draftText = text) }
    draftSaveJob?.cancel()
    draftSaveJob = scope.launch {
        delay(200)
        val currentState = _state.value
        val threadId = currentState.currentTopicId
        repositoryMessage.saveChatDraft(chatId, text, currentState.replyMessage?.id, threadId)
    }
}

internal fun DefaultChatComponent.handleSendReaction(messageId: Long, reaction: String) {
    val suppressUntil = System.currentTimeMillis() + REACTION_UPDATE_SUPPRESSION_MS
    reactionUpdateSuppressedUntil[messageId] = suppressUntil
    scope.launch {
        delay(REACTION_UPDATE_SUPPRESSION_MS)
        reactionUpdateSuppressedUntil.remove(messageId, suppressUntil)
    }

    _state.update { currentState ->
        val currentMessages = currentState.messages.toMutableList()
        val index = currentMessages.indexOfFirst { it.id == messageId }
        if (index == -1) return@update currentState

        val message = currentMessages[index]
        val isCustom = reaction.all { it.isDigit() }
        val emoji = if (isCustom) null else reaction
        val customEmojiId = if (isCustom) reaction.toLongOrNull() else null

        val existingReaction = message.reactions.find {
            (it.emoji != null && it.emoji == emoji) || (it.customEmojiId != null && it.customEmojiId == customEmojiId)
        }

        val isChosen = existingReaction?.isChosen ?: false

        val newReactions = message.reactions.toMutableList()
        if (isChosen) {
            val reactionToUpdate = existingReaction!!
            if (reactionToUpdate.count > 1) {
                val reactionIndex = newReactions.indexOf(reactionToUpdate)
                if (reactionIndex != -1) {
                    newReactions[reactionIndex] = reactionToUpdate.copy(
                        count = reactionToUpdate.count - 1,
                        isChosen = false
                    )
                }
            } else {
                newReactions.remove(reactionToUpdate)
            }
            scope.launch {
                repositoryMessage.removeMessageReaction(chatId, messageId, reaction)
            }
        } else {
            if (existingReaction != null) {
                val reactionIndex = newReactions.indexOf(existingReaction)
                if (reactionIndex != -1) {
                    newReactions[reactionIndex] = existingReaction.copy(
                        count = existingReaction.count + 1,
                        isChosen = true
                    )
                }
            } else {
                newReactions.add(
                    MessageReactionModel(
                        emoji = emoji,
                        customEmojiId = customEmojiId,
                        count = 1,
                        isChosen = true
                    )
                )
            }
            scope.launch {
                repositoryMessage.addMessageReaction(chatId, messageId, reaction)
            }
        }

        currentMessages[index] = message.copy(reactions = newReactions)
        currentState.copy(messages = currentMessages)
    }
}

internal fun DefaultChatComponent.handlePinMessage(message: MessageModel) {
    scope.launch {
        repositoryMessage.pinMessage(chatId, message.id)
    }
}

internal fun DefaultChatComponent.handleUnpinMessage(message: MessageModel) {
    scope.launch {
        repositoryMessage.unpinMessage(chatId, message.id)
    }
}

internal fun DefaultChatComponent.handleClearMessages() {
    chatOperationsRepository.clearChatHistory(chatId, false)
}

internal fun DefaultChatComponent.handleSendScheduledNow(message: MessageModel) {
    scope.launch {
        repositoryMessage.sendScheduledNow(chatId, message.id)
        loadScheduledMessages()
    }
}
