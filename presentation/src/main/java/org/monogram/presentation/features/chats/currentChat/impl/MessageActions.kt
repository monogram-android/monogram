package org.monogram.presentation.features.chats.currentChat.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent
import org.monogram.presentation.features.chats.currentChat.editor.video.VideoQuality
import org.monogram.presentation.features.chats.currentChat.editor.video.VideoTrimRange
import org.monogram.presentation.features.chats.currentChat.editor.video.processVideo
import java.io.File
import java.io.FileOutputStream

internal fun DefaultChatComponent.handleSendMessage(text: String, entities: List<MessageEntity>) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendMessage(chatId, text, replyId, entities, threadId)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleSendSticker(stickerId: String) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendSticker(chatId, stickerId, replyToMsgId = replyId, threadId = threadId)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleSendPhoto(photoPath: String, caption: String) {
    scope.launch {
        val shouldCompress = appPreferences.compressPhotos.value

        val finalPath = if (shouldCompress) {
            withContext(Dispatchers.IO) {
                try {
                    val bitmap = BitmapFactory.decodeFile(photoPath)
                    val compressedFile = File(cacheController.getCacheDir(), "compressed_photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(compressedFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    compressedFile.absolutePath
                } catch (e: Exception) {
                    photoPath
                }
            }
        } else {
            photoPath
        }

        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendPhoto(chatId, finalPath, caption = caption, replyToMsgId = replyId, threadId = threadId)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleSendVideo(videoPath: String, caption: String) {
    scope.launch {
        val shouldCompress = appPreferences.compressVideos.value

        val finalPath = if (shouldCompress) {
            processVideo(
                inputPath = videoPath,
                trimRange = VideoTrimRange(),
                filter = null,
                textElements = emptyList(),
                quality = VideoQuality.P720,
                muteAudio = false,
                context = this@handleSendVideo.cacheController.context
            )
        } else {
            videoPath
        }

        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendVideo(chatId, finalPath, caption = caption, replyToMsgId = replyId, threadId = threadId)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleSendGif(gif: GifModel) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendGif(chatId, gif.fileId.toString(), replyToMsgId = replyId, threadId = threadId)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleSendGifFile(path: String, caption: String) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendGifFile(chatId, path, caption = caption, replyToMsgId = replyId, threadId = threadId)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleSendAlbum(paths: List<String>, caption: String) {
    scope.launch {
        val compressPhotos = appPreferences.compressPhotos.value
        val compressVideos = appPreferences.compressVideos.value

        val processedPaths = paths.map { path ->
            when {
                path.endsWith(".mp4") -> {
                    if (compressVideos) {
                        processVideo(
                            inputPath = path,
                            trimRange = VideoTrimRange(),
                            filter = null,
                            textElements = emptyList(),
                            quality = VideoQuality.P720,
                            muteAudio = false,
                            context = this@handleSendAlbum.cacheController.context
                        )
                    } else path
                }

                path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") -> {
                    if (compressPhotos) {
                        withContext(Dispatchers.IO) {
                            try {
                                val bitmap = BitmapFactory.decodeFile(path)
                                val compressedFile =
                                    File(cacheController.getCacheDir(), "compressed_photo_${System.currentTimeMillis()}.jpg")
                                FileOutputStream(compressedFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                }
                                compressedFile.absolutePath
                            } catch (e: Exception) {
                                path
                            }
                        }
                    } else path
                }

                else -> path
            }
        }

        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendAlbum(
            chatId,
            processedPaths,
            caption = caption,
            replyToMsgId = replyId,
            threadId = threadId
        )
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleVideoRecorded(file: File) {
    scope.launch(Dispatchers.IO) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = timeString?.toLongOrNull() ?: 0L
            val durationSec = (durationMs / 1000).toInt()
            retriever.release()
            repositoryMessage.sendVideoNote(chatId, file.absolutePath, durationSec, 384)
            withContext(Dispatchers.Main) {
                if (!_state.value.isAtBottom) {
                    onScrollToBottom()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

internal fun DefaultChatComponent.handleSendVoice(path: String, duration: Int, waveform: ByteArray) {
    scope.launch {
        repositoryMessage.sendVoiceNote(chatId, path, duration, waveform)
        if (!_state.value.isAtBottom) {
            onScrollToBottom()
        }
    }
}

internal fun DefaultChatComponent.handleCopySelectedMessages(clipboardManager: ClipboardManager) {
    val currentState = _state.value
    val selectedIds = currentState.selectedMessageIds
    val selectedMessages = currentState.messages
        .filter { selectedIds.contains(it.id) }
        .sortedBy { it.id }

    val text = selectedMessages.joinToString("\n\n") { msg ->
        when (val content = msg.content) {
            is MessageContent.Text -> content.text
            is MessageContent.Photo -> content.caption
            is MessageContent.Video -> content.caption
            is MessageContent.Document -> content.caption
            is MessageContent.Gif -> content.caption
            else -> ""
        }
    }

    if (text.isNotEmpty()) {
        clipboardManager.setText(AnnotatedString(text))
    }
    onClearSelection()
}

internal fun DefaultChatComponent.handleReportMessage(message: MessageModel) {

}

internal fun DefaultChatComponent.handleReportReasonSelected(reason: String) {
    chatsListRepository.reportChat(chatId, reason)
}

internal fun DefaultChatComponent.handleCopyLink(clipboardManager: ClipboardManager) {
    scope.launch {
        val link = chatsListRepository.getChatLink(chatId)
        if (link != null) {
            clipboardManager.setText(AnnotatedString(link))
        }
    }
}
