package org.monogram.presentation.features.chats.currentChat.impl

import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent

internal fun DefaultChatComponent.handleDownloadFile(fileId: Int) {
    repositoryMessage.downloadFile(fileId, priority = 1)
}

internal fun DefaultChatComponent.handleCancelDownloadFile(fileId: Int) {
    scope.launch {
        repositoryMessage.cancelDownloadFile(fileId)
    }
}

internal fun DefaultChatComponent.handleDownloadHighRes(messageId: Long) {
    scope.launch {
        val fileId = repositoryMessage.getHighResFileId(chatId, messageId)
        if (fileId != null) {
            // High-res downloads are usually manual, so we use priority 32
            repositoryMessage.downloadFile(fileId, priority = 32)
        }
    }
}
