package org.monogram.presentation.features.chats.conversation.logic

import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.MessageContent
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent

internal fun DefaultChatComponent.handleDownloadFile(fileId: Int, userInitiated: Boolean = true) {
    if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) return
    // Only an explicit gesture gets priority 32 and the user-initiated fast path. Viewport
    // prefetch used to pass 32 as well, which marked every scrolled-past thumbnail as "manual"
    // and left it un-evictable, silting up the download slots.
    repositoryMessage.downloadFile(
        fileId,
        priority = if (userInitiated) 32 else 16,
        userInitiated = userInitiated
    )
}

internal fun DefaultChatComponent.handleCancelDownloadFile(fileId: Int) {
    if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) return
    scope.launch {
        try {
            repositoryMessage.cancelDownloadFile(fileId)
        } catch (e: Throwable) {
            Log.e("DownloadDebug", "CancelDownloadFile failed: fileId=$fileId chatId=$chatId", e)
        }
    }
}

internal fun DefaultChatComponent.handleDownloadHighRes(messageId: Long) {
    if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) return
    scope.launch {
        val fileId = repositoryMessage.getHighResFileId(chatId, messageId)
        if (fileId != null) {
            updatePhotoOriginalFileId(messageId, fileId)
        }
    }
}

private fun DefaultChatComponent.updatePhotoOriginalFileId(messageId: Long, originalFileId: Int) {
    if (originalFileId == 0) return
    _state.update { state ->
        state.copy(
            messages = state.messages.map { message ->
                if (message.id != messageId) return@map message
                val photo = message.content as? MessageContent.Photo ?: return@map message
                if (photo.originalFileId == originalFileId) {
                    message
                } else {
                    message.copy(content = photo.copy(originalFileId = originalFileId))
                }
            }
        )
    }
}
