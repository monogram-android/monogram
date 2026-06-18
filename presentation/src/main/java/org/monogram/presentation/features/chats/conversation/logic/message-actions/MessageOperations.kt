package org.monogram.presentation.features.chats.conversation.logic

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChecklistTask
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageReactionModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ChecklistDraft
import org.monogram.presentation.R
import org.monogram.presentation.features.chats.conversation.ChatComponent
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

private const val REACTION_UPDATE_SUPPRESSION_MS = 1500L


internal fun DefaultChatComponent.handleMessageVisible(messageId: Long) {
    scope.launch {
        val hadUnreadCount = _state.value.unreadCount > 0
        val visibleMessage = _state.value.messages.firstOrNull { it.id == messageId }
        val targetChatId = visibleMessage?.chatId ?: activeThreadChatId()
        val visibleMessageIds = if (visibleMessage != null && visibleMessage.mediaAlbumId != 0L) {
            _state.value.messages
                .asSequence()
                .filter { it.chatId == visibleMessage.chatId && it.mediaAlbumId == visibleMessage.mediaAlbumId }
                .map(MessageModel::id)
                .distinct()
                .sorted()
                .toList()
                .ifEmpty { listOf(messageId) }
        } else {
            listOf(messageId)
        }
        _state.update { state ->
            state.withVisibleMessagesRead(
                readChatId = targetChatId,
                visibleMessageIds = visibleMessageIds
            )
        }
        visibleMessageIds.forEach { repositoryMessage.markAsRead(targetChatId, it) }
        if (hadUnreadCount) {
            repositoryMessage.markAllMentionsAsRead(targetChatId)
            repositoryMessage.markAllReactionsAsRead(targetChatId)
        }

        visibleMessage?.let { requestSenderRefreshIfNeeded(it) }
    }
}

internal fun DefaultChatComponent.handleDeleteMessage(message: MessageModel, revoke: Boolean = false) {
    val messageIdsToDelete = if (message.mediaAlbumId != 0L) {
        _state.value.messages
            .asSequence()
            .filter { it.chatId == message.chatId && it.mediaAlbumId == message.mediaAlbumId }
            .map(MessageModel::id)
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { listOf(message.id) }
    } else {
        listOf(message.id)
    }

    scope.launch {
        repositoryMessage.deleteMessage(message.chatId, messageIdsToDelete, revoke)
    }
}

internal fun DefaultChatComponent.handleSaveEditedMessage(text: String, entities: List<MessageEntity>) {
    val editingMsg = _state.value.editingMessage ?: return
    scope.launch {
        when (editingMsg.content) {
            is MessageContent.Photo,
            is MessageContent.Video,
            is MessageContent.Document,
            is MessageContent.Audio,
            is MessageContent.Gif -> repositoryMessage.editMessageCaption(chatId, editingMsg.id, text, entities)

            else -> repositoryMessage.editMessage(chatId, editingMsg.id, text, entities)
        }
        onCancelEdit()
    }
}

internal fun DefaultChatComponent.handleSaveChecklistDraft(draft: ChecklistDraft) {
    val checklistMessage = _state.value.checklistMessage
    Log.d(
        "ChecklistFlow",
        "save_draft messageId=${checklistMessage?.id} title=${draft.title} tasks=${draft.tasks.size}"
    )
    if (checklistMessage != null) {
        _state.update { state ->
            state.withUpdatedChecklistDraft(checklistMessage.id, draft)
                .copy(checklistMessage = null, checklistDraft = null)
        }
    } else {
        _state.update { state -> state.copy(checklistMessage = null, checklistDraft = null) }
    }
    scope.launch {
        runCatching {
            if (checklistMessage != null) {
                repositoryMessage.editChecklistMessage(
                    chatId = chatId,
                    messageId = checklistMessage.id,
                    checklistDraft = draft
                )
            } else {
                val currentState = _state.value
                repositoryMessage.sendChecklist(
                    chatId = currentState.effectiveThreadChatId(chatId),
                    checklistDraft = draft,
                    replyToMsgId = currentState.replyMessage?.id,
                    threadId = currentState.effectiveThreadId()
                )
                onCancelReply()
                if (currentState.rootMessage == null && !currentState.isAtBottom) {
                    onScrollToBottom()
                }
            }
        }.onSuccess {
            Log.d("ChecklistFlow", "save_draft_success messageId=${checklistMessage?.id}")
        }.onFailure { error ->
            Log.e("ChecklistFlow", "save_draft_failed messageId=${checklistMessage?.id}", error)
        }
    }
}

internal fun DefaultChatComponent.handleToggleChecklistTask(
    messageId: Long,
    taskId: Int,
    isDone: Boolean
) {
    Log.d(
        "ChecklistFlow",
        "toggle_task messageId=$messageId taskId=$taskId isDone=$isDone"
    )
    val currentUser = _state.value.currentUser
    _state.update { state ->
        state.withUpdatedChecklistTask(
            messageId = messageId,
            taskId = taskId,
            isDone = isDone,
            currentUser = currentUser,
            fallbackName = cacheController.context.getString(R.string.label_you)
        )
    }
    scope.launch {
        runCatching {
            repositoryMessage.markChecklistTasksAsDone(
                chatId = chatId,
                messageId = messageId,
                doneIds = if (isDone) listOf(taskId) else emptyList(),
                undoneIds = if (isDone) emptyList() else listOf(taskId)
            )
        }.onSuccess {
            Log.d(
                "ChecklistFlow",
                "toggle_task_success messageId=$messageId taskId=$taskId isDone=$isDone"
            )
        }.onFailure { error ->
            Log.e(
                "ChecklistFlow",
                "toggle_task_failed messageId=$messageId taskId=$taskId isDone=$isDone",
                error
            )
        }
    }
}

private fun ChatComponent.State.withUpdatedChecklistTask(
    messageId: Long,
    taskId: Int,
    isDone: Boolean,
    currentUser: UserModel?,
    fallbackName: String
): ChatComponent.State {
    return copy(
        messages = messages.map {
            it.withUpdatedChecklistTask(
                messageId,
                taskId,
                isDone,
                currentUser,
                fallbackName
            )
        },
        rootMessage = rootMessage?.withUpdatedChecklistTask(
            messageId,
            taskId,
            isDone,
            currentUser,
            fallbackName
        ),
        checklistMessage = checklistMessage?.withUpdatedChecklistTask(
            messageId,
            taskId,
            isDone,
            currentUser,
            fallbackName
        )
    )
}

private fun ChatComponent.State.withUpdatedChecklistDraft(
    messageId: Long,
    draft: ChecklistDraft
): ChatComponent.State {
    return copy(
        messages = messages.map { it.withUpdatedChecklistDraft(messageId, draft) },
        rootMessage = rootMessage?.withUpdatedChecklistDraft(messageId, draft),
        checklistMessage = checklistMessage?.withUpdatedChecklistDraft(messageId, draft)
    )
}

private fun MessageModel.withUpdatedChecklistTask(
    messageId: Long,
    taskId: Int,
    isDone: Boolean,
    currentUser: UserModel?,
    fallbackName: String
): MessageModel {
    if (id != messageId) return this
    val checklist = content as? MessageContent.Checklist ?: return this
    val updatedTasks = checklist.tasks.map { task ->
        if (task.id == taskId) {
            task.withCompletion(isDone, currentUser, fallbackName)
        } else {
            task
        }
    }
    return copy(content = checklist.copy(tasks = updatedTasks))
}

private fun MessageModel.withUpdatedChecklistDraft(
    messageId: Long,
    draft: ChecklistDraft
): MessageModel {
    if (id != messageId) return this
    val checklist = content as? MessageContent.Checklist ?: return this
    val previousTasks = checklist.tasks.associateBy(ChecklistTask::id)
    val updatedTasks = draft.tasks.map { task ->
        val previous = previousTasks[task.id]
        ChecklistTask(
            id = task.id,
            text = task.text,
            entities = task.entities,
            completedById = previous?.completedById,
            completedByName = previous?.completedByName,
            completionDate = previous?.completionDate ?: 0
        )
    }
    return copy(
        content = checklist.copy(
            title = draft.title,
            titleEntities = draft.titleEntities,
            tasks = updatedTasks,
            othersCanAddTasks = draft.othersCanAddTasks,
            othersCanMarkTasksAsDone = draft.othersCanMarkTasksAsDone
        )
    )
}

private fun ChecklistTask.withCompletion(
    isDone: Boolean,
    currentUser: UserModel?,
    fallbackName: String
): ChecklistTask {
    if (!isDone) {
        return copy(completedById = null, completedByName = null, completionDate = 0)
    }

    val name = currentUser?.let { user ->
        listOf(user.firstName, user.lastName)
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { user.username.orEmpty() }
            .ifBlank { fallbackName }
    } ?: fallbackName

    return copy(
        completedById = currentUser?.id ?: 0L,
        completedByName = name,
        completionDate = (System.currentTimeMillis() / 1000L).toInt()
    )
}

internal fun DefaultChatComponent.handleDraftChange(text: String) {
    val isEditing = _state.value.editingMessage != null
    recomputeDraftLinkPreview(
        text = text,
        updateDraftText = !isEditing
    )
    if (isEditing) return

    draftSaveJob?.cancel()
    draftSaveJob = scope.launch {
        delay(200)
        val currentState = _state.value
        repositoryMessage.saveChatDraft(
            currentState.effectiveThreadChatId(chatId),
            text,
            currentState.replyMessage?.id,
            currentState.effectiveThreadId()
        )
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
    scope.launch {
        chatOperationsRepository.clearChatHistory(chatId, false)
    }
}

internal fun DefaultChatComponent.handleSendScheduledNow(message: MessageModel) {
    scope.launch {
        repositoryMessage.sendScheduledNow(chatId, message.id)
        loadScheduledMessages()
    }
}
