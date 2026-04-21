package org.monogram.presentation.features.chats.currentChat.impl

import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.currentChat.ChatComponent
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun ChatComponent.State.effectiveThreadChatId(baseChatId: Long): Long {
    return currentThreadChatId ?: baseChatId
}

internal fun ChatComponent.State.effectiveThreadId(): Long? {
    return currentMessageThreadId ?: currentTopicId
}

internal fun ChatComponent.State.isMessageInActiveThread(
    baseChatId: Long,
    message: MessageModel
): Boolean {
    val targetThreadId =
        effectiveThreadId() ?: return message.chatId == effectiveThreadChatId(baseChatId)
    return message.chatId == effectiveThreadChatId(baseChatId) && message.threadId == targetThreadId
}

internal fun DefaultChatComponent.activeThreadChatId(): Long =
    _state.value.effectiveThreadChatId(chatId)

internal fun DefaultChatComponent.activeThreadId(): Long? = _state.value.effectiveThreadId()
