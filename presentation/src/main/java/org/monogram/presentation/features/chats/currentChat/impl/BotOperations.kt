package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.*
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.handleMentionQueryChange(
    query: String?,
    allMembers: List<UserModel>,
    onMembersUpdated: (List<UserModel>) -> Unit
): Job? {
    if (query == null) {
        _state.update { it.copy(mentionSuggestions = emptyList()) }
        return null
    }

    return scope.launch {
        delay(150)
        val currentState = _state.value
        val suggestions = if (query.isEmpty()) {
            if (allMembers.isEmpty()) {
                val canLoadMembers = !currentState.isChannel || currentState.isAdmin
                if (canLoadMembers && (currentState.isGroup || currentState.isChannel)) {
                    try {
                        val members = userRepository.getChatMembers(chatId, 0, 200, ChatMembersFilter.Recent)
                            .map { it.user }
                        onMembersUpdated(members)
                        members
                    } catch (e: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            } else {
                allMembers
            }
        } else {
            val lowerQuery = query.lowercase()
            val filtered = allMembers.filter {
                it.firstName.lowercase().contains(lowerQuery) ||
                        it.lastName?.lowercase()?.contains(lowerQuery) == true ||
                        it.username?.lowercase()?.contains(lowerQuery) == true
            }
            if (filtered.isEmpty() && query.length > 2) {
                try {
                    val searchResults = userRepository.searchContacts(query)
                    searchResults
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                filtered
            }
        }
        _state.update { it.copy(mentionSuggestions = suggestions) }
    }
}

internal fun DefaultChatComponent.handleInlineQueryChange(botUsername: String, query: String) {
    inlineBotJob?.cancel()
    inlineBotJob = scope.launch {
        delay(400)
        _state.update { it.copy(isInlineBotLoading = true) }
        try {
            val bot = userRepository.searchPublicChat(botUsername)
            if (bot != null) {
                val results = repositoryMessage.getInlineBotResults(bot.id, chatId, query)
                _state.update { it.copy(
                    inlineBotResults = results,
                    currentInlineBotId = bot.id,
                    currentInlineQuery = query
                ) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _state.update { it.copy(isInlineBotLoading = false) }
        }
    }
}

internal fun DefaultChatComponent.handleLoadMoreInlineResults(offset: String) {
    val botId = _state.value.currentInlineBotId ?: return
    val query = _state.value.currentInlineQuery ?: return

    inlineBotJob?.cancel()
    inlineBotJob = scope.launch {
        _state.update { it.copy(isInlineBotLoading = true) }
        try {
            val results = repositoryMessage.getInlineBotResults(botId, chatId, query, offset)
            if (results != null) {
                _state.update { currentState ->
                    val currentResults = currentState.inlineBotResults
                    if (currentResults != null) {
                        currentState.copy(
                            inlineBotResults = results.copy(results = currentResults.results + results.results)
                        )
                    } else {
                        currentState.copy(inlineBotResults = results)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            _state.update { it.copy(isInlineBotLoading = false) }
        }
    }
}

internal fun DefaultChatComponent.handleSendInlineResult(resultId: String) {
    val results = _state.value.inlineBotResults ?: return
    scope.launch {
        repositoryMessage.sendInlineBotResult(
            chatId = chatId,
            queryId = results.queryId,
            resultId = resultId,
            replyToMsgId = _state.value.replyMessage?.id,
            threadId = _state.value.currentTopicId
        )
    }
    _state.update { it.copy(
        inlineBotResults = null,
        currentInlineBotId = null,
        currentInlineQuery = null,
        replyMessage = null
    ) }
}

internal fun DefaultChatComponent.handleReplyMarkupButtonClick(
    messageId: Long,
    button: InlineKeyboardButtonModel,
    botUserId: Long
) {
    scope.launch {
        when (val type = button.type) {
            is InlineKeyboardButtonType.Callback -> {
                repositoryMessage.onCallbackQuery(chatId, messageId, type.data)
            }

            is InlineKeyboardButtonType.Url -> {
                onLinkClick(type.url)
            }

            is InlineKeyboardButtonType.WebApp -> {
                onOpenMiniApp(type.url, button.text, botUserId)
            }

            is InlineKeyboardButtonType.Buy -> {
                repositoryMessage.onCallbackQueryBuy(chatId, messageId)
            }

            is InlineKeyboardButtonType.User -> {
                toProfile(type.userId)
            }

            else -> {
                Log.e("DefaultChatComponent", "Unknown inline keyboard button type: $type")
            }
        }
    }
}

internal fun DefaultChatComponent.handleKeyboardButtonClick(
    messageId: Long,
    button: KeyboardButtonModel,
    botUserId: Long
) {
    scope.launch {
        when (val type = button.type) {
            is KeyboardButtonType.Text -> {
                onSendMessage(button.text)
            }

            is KeyboardButtonType.WebApp -> {
                onOpenMiniApp(type.url, button.text, botUserId)
            }

            else -> {
                Log.e("DefaultChatComponent", "Unknown keyboard button type: $type")
            }
        }
    }
}

internal fun DefaultChatComponent.handleBotCommandClick(command: String) {
    onSendMessage(command)
}
