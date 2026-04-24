package org.monogram.presentation.features.chats.conversation.logic

import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

internal fun DefaultChatComponent.handlePollOptionClick(messageId: Long, optionId: Int) {
    scope.launch {
        repositoryMessage.setPollAnswer(chatId, messageId, listOf(optionId))
    }
}

internal fun DefaultChatComponent.handleRetractVote(messageId: Long) {
    scope.launch {
        repositoryMessage.setPollAnswer(chatId, messageId, emptyList())
    }
}

internal fun DefaultChatComponent.handleShowVoters(messageId: Long, optionId: Int) {
    scope.launch {
        _state.update { it.copy(
            showPollVoters = true,
            pollVoters = emptyList(),
            isPollVotersLoading = true
        ) }
        val voters = repositoryMessage.getPollVoters(chatId, messageId, optionId, 0, 50)
        _state.update { it.copy(
            pollVoters = voters,
            isPollVotersLoading = false
        ) }
    }
}

internal fun DefaultChatComponent.handleClosePoll(messageId: Long) {
    scope.launch {
        repositoryMessage.stopPoll(chatId, messageId)
    }
}
