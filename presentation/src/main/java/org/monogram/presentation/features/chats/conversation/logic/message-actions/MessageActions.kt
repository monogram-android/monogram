package org.monogram.presentation.features.chats.conversation.logic

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.PollDraft
import org.monogram.presentation.features.chats.common.ChatActionType
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent
import org.monogram.presentation.features.chats.conversation.editor.video.VideoQuality
import org.monogram.presentation.features.chats.conversation.editor.video.VideoTrimRange
import org.monogram.presentation.features.chats.conversation.editor.video.processVideo
import org.monogram.presentation.features.share.PendingAttachment
import org.monogram.presentation.features.share.PendingAttachmentKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal sealed interface PendingAttachmentSendPlan {
    data class Single(val attachment: PendingAttachment) : PendingAttachmentSendPlan
    data class Album(val attachments: List<PendingAttachment>) : PendingAttachmentSendPlan
    data class Individual(val attachments: List<PendingAttachment>) : PendingAttachmentSendPlan
}

private fun DefaultChatComponent.shouldAutoScrollAfterSend(isAtBottom: Boolean): Boolean {
    return _state.value.rootMessage == null && !isAtBottom
}

internal fun DefaultChatComponent.handleSendMessage(
    text: String,
    entities: List<MessageEntity>,
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendMessage(targetChatId, text, replyId, entities, threadId, sendOptions)
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        onCancelReply()
        repositoryMessage.sendSticker(
            targetChatId,
            stickerId,
            replyToMsgId = replyId,
            threadId = threadId
        )
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
        val matchingAttachment =
            _state.value.stagedAttachments.firstOrNull { it.localPath == photoPath }
        val shouldCompress = appPreferences.compressPhotos.value

        val finalPath = if (shouldCompress) {
            withContext(Dispatchers.IO) {
                compressPhotoForUpload(photoPath) ?: photoPath
            }
        } else {
            photoPath
        }

        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendPhoto(
            chatId = targetChatId,
            photoPath = finalPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
        _state.update { state ->
            state.copy(stagedAttachments = state.stagedAttachments.filterNot { it.localPath == photoPath })
        }
        if (sendOptions.scheduleDate == null) {
            cleanupTempAttachments(listOfNotNull(matchingAttachment))
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
        val matchingAttachment =
            _state.value.stagedAttachments.firstOrNull { it.localPath == videoPath }
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
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendVideo(
            chatId = targetChatId,
            videoPath = finalPath,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
        _state.update { state ->
            state.copy(stagedAttachments = state.stagedAttachments.filterNot { it.localPath == videoPath })
        }
        if (sendOptions.scheduleDate == null) {
            cleanupTempAttachments(listOfNotNull(matchingAttachment))
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
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        val inlineQueryId = gif.inlineQueryId
        if (inlineQueryId != null) {
            inlineBotRepository.sendInlineBotResult(
                chatId = targetChatId,
                queryId = inlineQueryId,
                resultId = gif.id,
                replyToMsgId = replyId,
                threadId = threadId
            )
        } else {
            repositoryMessage.sendGif(
                targetChatId,
                gif.fileId.toString(),
                replyToMsgId = replyId,
                threadId = threadId,
                sendOptions = sendOptions
            )
        }
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
    }
}

internal fun DefaultChatComponent.handleSendDocument(
    path: String,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val matchingAttachment = _state.value.stagedAttachments.firstOrNull { it.localPath == path }
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendDocument(
            chatId = targetChatId,
            documentPath = path,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
        _state.update { state ->
            state.copy(stagedAttachments = state.stagedAttachments.filterNot { it.localPath == path })
        }
        if (sendOptions.scheduleDate == null) {
            cleanupTempAttachments(listOfNotNull(matchingAttachment))
        }
    }
}

internal fun DefaultChatComponent.handleSendPoll(
    poll: PollDraft,
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendPoll(
            chatId = targetChatId,
            poll = poll,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
        val matchingAttachment = _state.value.stagedAttachments.firstOrNull { it.localPath == path }
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendGifFile(
            chatId = targetChatId,
            gifPath = path,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
        _state.update { state ->
            state.copy(stagedAttachments = state.stagedAttachments.filterNot { it.localPath == path })
        }
        if (sendOptions.scheduleDate == null) {
            cleanupTempAttachments(listOfNotNull(matchingAttachment))
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
        val matchingAttachments = _state.value.stagedAttachments.filter { it.localPath in paths }
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
                            compressPhotoForUpload(path) ?: path
                        }
                    } else path
                }

                else -> path
            }
        }

        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendAlbum(
            targetChatId,
            processedPaths,
            caption = caption,
            captionEntities = captionEntities,
            replyToMsgId = replyId,
            threadId = threadId,
            sendOptions = sendOptions
        )
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
            onScrollToBottom()
        }
        if (sendOptions.scheduleDate != null) {
            loadScheduledMessages()
        }
        _state.update { state ->
            state.copy(stagedAttachments = state.stagedAttachments.filterNot { it.localPath in paths })
        }
        if (sendOptions.scheduleDate == null) {
            cleanupTempAttachments(matchingAttachments)
        }
    }
}

internal fun DefaultChatComponent.handleSendPendingAttachments(
    attachments: List<PendingAttachment>,
    caption: String,
    captionEntities: List<MessageEntity>,
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    when (val plan = resolvePendingAttachmentSendPlan(attachments) ?: return) {
        is PendingAttachmentSendPlan.Album -> handleSendAlbum(
            paths = plan.attachments.map { it.localPath },
            caption = caption,
            captionEntities = captionEntities,
            sendOptions = sendOptions
        )

        is PendingAttachmentSendPlan.Single -> {
            when (plan.attachment.kind) {
                PendingAttachmentKind.PHOTO -> handleSendPhoto(
                    photoPath = plan.attachment.localPath,
                    caption = caption,
                    captionEntities = captionEntities,
                    sendOptions = sendOptions
                )

                PendingAttachmentKind.VIDEO -> handleSendVideo(
                    videoPath = plan.attachment.localPath,
                    caption = caption,
                    captionEntities = captionEntities,
                    sendOptions = sendOptions
                )

                PendingAttachmentKind.GIF -> handleSendGifFile(
                    path = plan.attachment.localPath,
                    caption = caption,
                    captionEntities = captionEntities,
                    sendOptions = sendOptions
                )

                PendingAttachmentKind.DOCUMENT -> handleSendDocument(
                    path = plan.attachment.localPath,
                    caption = caption,
                    captionEntities = captionEntities,
                    sendOptions = sendOptions
                )
            }
        }

        is PendingAttachmentSendPlan.Individual -> {
            plan.attachments.forEachIndexed { index, attachment ->
                val itemCaption = if (index == 0) caption else ""
                val itemEntities = if (index == 0) captionEntities else emptyList()
                when (attachment.kind) {
                    PendingAttachmentKind.PHOTO -> handleSendPhoto(
                        photoPath = attachment.localPath,
                        caption = itemCaption,
                        captionEntities = itemEntities,
                        sendOptions = sendOptions
                    )

                    PendingAttachmentKind.VIDEO -> handleSendVideo(
                        videoPath = attachment.localPath,
                        caption = itemCaption,
                        captionEntities = itemEntities,
                        sendOptions = sendOptions
                    )

                    PendingAttachmentKind.GIF -> handleSendGifFile(
                        path = attachment.localPath,
                        caption = itemCaption,
                        captionEntities = itemEntities,
                        sendOptions = sendOptions
                    )

                    PendingAttachmentKind.DOCUMENT -> handleSendDocument(
                        path = attachment.localPath,
                        caption = itemCaption,
                        captionEntities = itemEntities,
                        sendOptions = sendOptions
                    )
                }
            }
        }
    }
}

internal fun resolvePendingAttachmentSendPlan(
    attachments: List<PendingAttachment>
): PendingAttachmentSendPlan? {
    if (attachments.isEmpty()) return null
    if (attachments.size == 1) return PendingAttachmentSendPlan.Single(attachments.first())

    val media = attachments.filter { it.kind != PendingAttachmentKind.DOCUMENT }
    val hasDocuments = attachments.any { it.kind == PendingAttachmentKind.DOCUMENT }
    val hasGifs = attachments.any { it.kind == PendingAttachmentKind.GIF }
    val canSendAsAlbum = !hasDocuments &&
            !hasGifs &&
            media.size > 1 &&
            media.all { it.kind.isAlbumMedia }

    return if (canSendAsAlbum) {
        PendingAttachmentSendPlan.Album(attachments)
    } else {
        PendingAttachmentSendPlan.Individual(attachments)
    }
}

private fun DefaultChatComponent.compressPhotoForUpload(photoPath: String): String? {
    return try {
        val sourceBitmap = BitmapFactory.decodeFile(photoPath) ?: return null
        val normalizedBitmap = sourceBitmap.applyExifOrientation(photoPath)
        if (normalizedBitmap !== sourceBitmap) {
            sourceBitmap.recycle()
        }

        val compressedFile = File(
            cacheController.getCacheDir(),
            "compressed_photo_${System.currentTimeMillis()}.jpg"
        )
        FileOutputStream(compressedFile).use { out ->
            normalizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        if (!normalizedBitmap.isRecycled) {
            normalizedBitmap.recycle()
        }
        compressedFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

private fun Bitmap.applyExifOrientation(path: String): Bitmap {
    val orientation = try {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postScale(-1f, 1f)
                postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postScale(-1f, 1f)
                postRotate(90f)
            }
        }
    }

    if (matrix.isIdentity) return this
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

internal fun DefaultChatComponent.handleVideoRecorded(file: File) {
    scope.launch(Dispatchers.IO) {
        try {
            val currentState = _state.value
            val replyId = currentState.replyMessage?.id
            val threadId = currentState.effectiveThreadId()
            val targetChatId = currentState.effectiveThreadChatId(chatId)
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = timeString?.toLongOrNull() ?: 0L
            val durationSec = (durationMs / 1000).toInt()
            retriever.release()
            repositoryMessage.sendVideoNote(
                targetChatId,
                file.absolutePath,
                durationSec,
                384,
                replyToMsgId = replyId,
                threadId = threadId
            )
            withContext(Dispatchers.Main) {
                onCancelReply()
                if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        repositoryMessage.sendVoiceNote(
            targetChatId,
            path,
            duration,
            waveform,
            replyToMsgId = replyId,
            threadId = threadId
        )
        onCancelReply()
        if (shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
    runChatAction(ChatActionType.Report) {
        chatOperationsRepository.reportChat(chatId, reason)
        _state.update { it.copy(showReportDialog = false) }
    }
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
        repositoryMessage.forwardMessage(chatId, chatId, message.id, sendCopy = true)
    }
}
