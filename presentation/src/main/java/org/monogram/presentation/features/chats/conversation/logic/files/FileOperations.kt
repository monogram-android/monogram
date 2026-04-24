package org.monogram.presentation.features.chats.conversation.logic

import android.util.Log
import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

internal fun DefaultChatComponent.handleDownloadFile(fileId: Int) {
    repositoryMessage.downloadFile(fileId, priority = 32)
}

internal fun DefaultChatComponent.handleCancelDownloadFile(fileId: Int) {
    scope.launch {
        try {
            repositoryMessage.cancelDownloadFile(fileId)
        } catch (e: Throwable) {
            Log.e("DownloadDebug", "CancelDownloadFile failed: fileId=$fileId chatId=$chatId", e)
        }
    }
}

internal fun DefaultChatComponent.handleDownloadHighRes(messageId: Long) {
    scope.launch {
        val fileId = repositoryMessage.getHighResFileId(chatId, messageId)
        if (fileId != null) {
            repositoryMessage.downloadFile(fileId, priority = 32)
        }
    }
}
