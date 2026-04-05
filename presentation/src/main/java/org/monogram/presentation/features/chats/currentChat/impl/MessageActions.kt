package org.monogram.presentation.features.chats.currentChat.impl

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.models.*
import org.monogram.presentation.features.chats.currentChat.DefaultChatComponent
import org.monogram.presentation.features.chats.currentChat.editor.video.VideoQuality
import org.monogram.presentation.features.chats.currentChat.editor.video.VideoTrimRange
import org.monogram.presentation.features.chats.currentChat.editor.video.processVideo
import java.io.File
import java.io.FileOutputStream

internal fun DefaultChatComponent.handleSendMessage(
    text: String,
    entities: List<MessageEntity>,
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendMessage(chatId, text, replyId, entities, threadId, sendOptions)
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
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

internal fun DefaultChatComponent.handleSendPhoto(
    photoPath: String,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val shouldCompress = appPreferences.compressPhotos.value

        val finalPath = if (shouldCompress) {
            withContext(Dispatchers.IO) {
                try {
                    val bitmap = BitmapFactory.decodeFile(photoPath)
                    val compressedFile = File(cacheController.getCacheDir(), "compressed_photo_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(compressedFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
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
        repositoryMessage.sendPhoto(
            chatId = chatId,
            photoPath = finalPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
    }
}

internal fun DefaultChatComponent.handleSendVideo(
    videoPath: String,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val shouldCompress = appPreferences.compressVideos.value

        val finalPath = if (shouldCompress) {
            processVideo(
                inputPath = videoPath,
                trimRange = VideoTrimRange(),
                filter = null,
                textElements = emptyList(),
                quality = VideoQuality.P1080,
                muteAudio = false,
                context = this@handleSendVideo.cacheController.context
            )
        } else {
            videoPath
        }

        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendVideo(
            chatId = chatId,
            videoPath = finalPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
    }
}

internal fun DefaultChatComponent.handleSendGif(
    gif: GifModel,
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendGif(
            chatId,
            gif.fileId.toString(),
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
    }
}

internal fun DefaultChatComponent.handleSendGifFile(
    path: String,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.currentTopicId
        repositoryMessage.sendGifFile(
            chatId = chatId,
            gifPath = path,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
    }
}

internal fun DefaultChatComponent.handleSendAlbum(
    paths: List<String>,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
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
                            quality = VideoQuality.P1080,
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
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
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
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (!currentState.isAtBottom) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
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

internal fun DefaultChatComponent.handleCopySelectedMessages(localClipboard: Clipboard) {
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
        val clip = ClipData.newPlainText("", AnnotatedString(text))
        localClipboard.nativeClipboard.setPrimaryClip(clip)
    }
    onClearSelection()
}

internal fun DefaultChatComponent.handleReportMessage(message: MessageModel) {
    _state.update { it.copy(showReportDialog = true) }
}

internal fun DefaultChatComponent.handleReportReasonSelected(reason: String) {
    chatOperationsRepository.reportChat(chatId, reason)
}

internal fun DefaultChatComponent.handleCopyLink(localClipboard: Clipboard) {
    scope.launch {
        val link = chatOperationsRepository.getChatLink(chatId)
        if (link != null) {
            localClipboard.nativeClipboard.setPrimaryClip(
                ClipData.newPlainText("", AnnotatedString(link))
            )
        }
    }
}

internal fun DefaultChatComponent.handleRepeatMessage(message: MessageModel) {
    scope.launch {
        repositoryMessage.forwardMessage(chatId, chatId, message.id)
    }
}
