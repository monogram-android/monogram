package org.monogram.presentation.features.chats.currentChat.impl

import android.util.Log
import kotlinx.coroutines.launch
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent
import org.monogram.presentation.features.chats.currentChat.DownloadDebug

internal fun DefaultChatComponent.handleDownloadFile(fileId: Int) {
    Log.d(DownloadDebug.TAG, "handleDownloadFile: fileId=$fileId chatId=$chatId")
    repositoryMessage.downloadFile(fileId, priority = 32)
}

internal fun DefaultChatComponent.handleCancelDownloadFile(fileId: Int) {
    scope.launch {
        try {
            repositoryMessage.cancelDownloadFile(fileId)
            Log.d(DownloadDebug.TAG, "CancelDownloadFile sent: fileId=$fileId chatId=$chatId")
        } catch (e: Throwable) {
            Log.e(DownloadDebug.TAG, "CancelDownloadFile failed: fileId=$fileId chatId=$chatId", e)
        }
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
