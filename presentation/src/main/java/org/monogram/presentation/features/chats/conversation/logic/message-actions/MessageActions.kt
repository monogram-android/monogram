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
import org.monogram.core.telegram.TelegramLinkDomains
import org.monogram.domain.models.GifModel
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageEntity
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendOptions
import org.monogram.domain.models.PollDraft
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.domain.repository.RichTextParseMode
import org.monogram.presentation.features.chats.common.ChatActionType
import org.monogram.presentation.features.chats.conversation.DefaultChatComponent
import org.monogram.presentation.features.chats.conversation.OutgoingMessageReducer
import org.monogram.presentation.features.chats.conversation.editor.video.VideoQuality
import org.monogram.presentation.features.chats.conversation.editor.video.VideoTrimRange
import org.monogram.presentation.features.chats.conversation.editor.video.processVideo
import org.monogram.presentation.features.share.PendingAttachment
import org.monogram.presentation.features.share.PendingAttachmentKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

private const val MaxCompressedPhotoLongSide = 3840
private const val MtProtoPlainTextLimit = 4_096

internal fun DefaultChatComponent.ensureTdLibTextLimit(
    text: String,
    limit: Int?,
    label: String
): Boolean {
    if (limit != null && text.length > limit) {
        toastMessageDisplayer.show("$label is too long. Maximum is $limit characters")
        return false
    }
    return true
}

internal fun DefaultChatComponent.ensureTdLibMessageLimit(
    text: String,
    rich: Boolean
): Boolean {
    if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) {
        return ensureTdLibTextLimit(text, MtProtoPlainTextLimit, "Message")
    }
    val limits = tdLibLimitsRepository.limits.value
    return ensureTdLibTextLimit(
        text = text,
        limit = if (rich) limits.richMessageTextLengthMax else limits.messageTextLengthMax,
        label = if (rich) "Rich message" else "Message"
    )
}

internal fun DefaultChatComponent.ensureTdLibCaptionLimit(caption: String): Boolean =
    ensureTdLibTextLimit(
        text = caption,
        limit = tdLibLimitsRepository.limits.value.messageCaptionLengthMax,
        label = "Caption"
    )
internal data class PhotoCompressionProfile(
    val targetWidth: Int,
    val targetHeight: Int,
    val quality: Int
)

internal sealed interface PendingAttachmentSendPlan {
    data class Single(val attachment: PendingAttachment) : PendingAttachmentSendPlan
    data class Album(val attachments: List<PendingAttachment>) : PendingAttachmentSendPlan
    data class Individual(val attachments: List<PendingAttachment>) : PendingAttachmentSendPlan
}

internal class PendingAttachmentSendRegistry {
    private val inFlightKeys = mutableSetOf<String>()

    fun start(key: String): Boolean = synchronized(this) {
        inFlightKeys.add(key)
    }

    fun finish(key: String) = synchronized(this) {
        inFlightKeys.remove(key)
    }
}

private data class RemovedStagedAttachments(
    val attachments: List<PendingAttachment>,
    val insertAt: Int
)

private fun DefaultChatComponent.shouldAutoScrollAfterSend(isAtBottom: Boolean): Boolean {
    return _state.value.rootMessage == null && !isAtBottom
}

private fun DefaultChatComponent.takeStagedAttachments(paths: Collection<String>): RemovedStagedAttachments {
    if (paths.isEmpty()) return RemovedStagedAttachments(emptyList(), 0)
    val pathSet = paths.toSet()
    val stagedAttachments = _state.value.stagedAttachments
    val removed = stagedAttachments.filter { it.localPath in pathSet }
    if (removed.isEmpty()) return RemovedStagedAttachments(emptyList(), stagedAttachments.size)
    val insertAt = stagedAttachments.indexOfFirst { it.localPath in pathSet }.coerceAtLeast(0)
    _state.update { state ->
        state.copy(stagedAttachments = state.stagedAttachments.filterNot { it.localPath in pathSet })
    }
    return RemovedStagedAttachments(attachments = removed, insertAt = insertAt)
}

private fun DefaultChatComponent.restoreStagedAttachments(removed: RemovedStagedAttachments) {
    if (removed.attachments.isEmpty()) return
    _state.update { state ->
        val merged = state.stagedAttachments.toMutableList()
        val restored = removed.attachments.filter { attachment ->
            merged.none { it.localPath == attachment.localPath }
        }
        if (restored.isEmpty()) return@update state
        val insertAt = removed.insertAt.coerceIn(0, merged.size)
        merged.addAll(insertAt, restored)
        state.copy(stagedAttachments = merged)
    }
}

private fun buildPendingAttachmentSendToken(
    operation: String,
    targetChatId: Long,
    threadId: Long?,
    paths: List<String>,
    caption: String,
    captionEntities: List<MessageEntity>,
    sendOptions: MessageSendOptions
): String {
    return buildString {
        append(operation)
        append('|')
        append(targetChatId)
        append('|')
        append(threadId ?: 0L)
        append('|')
        append(paths.joinToString(separator = "\u001F"))
        append('|')
        append(caption)
        append('|')
        append(captionEntities.hashCode())
        append('|')
        append(sendOptions.hashCode())
    }
}

private inline fun DefaultChatComponent.launchPendingAttachmentSend(
    operation: String,
    paths: List<String>,
    caption: String,
    captionEntities: List<MessageEntity>,
    sendOptions: MessageSendOptions,
    crossinline block: suspend () -> Unit
) {
    val currentState = _state.value
    val threadId = currentState.effectiveThreadId()
    val targetChatId = currentState.effectiveThreadChatId(chatId)
    val sendToken = buildPendingAttachmentSendToken(
        operation = operation,
        targetChatId = targetChatId,
        threadId = threadId,
        paths = paths,
        caption = caption,
        captionEntities = captionEntities,
        sendOptions = sendOptions
    )

    if (!pendingAttachmentSendRegistry.start(sendToken)) return

    scope.launch {
        try {
            block()
        } finally {
            pendingAttachmentSendRegistry.finish(sendToken)
        }
    }
}

internal fun DefaultChatComponent.handleSendMessage(
    text: String,
    entities: List<MessageEntity>,
    sendOptions: MessageSendOptions = MessageSendOptions(),
    parseMode: RichTextParseMode? = null
) {
    if (!ensureTdLibMessageLimit(text, rich = parseMode != null)) return
    scope.launch {
        val currentState = _state.value
        val replyId = currentState.replyMessage?.id
        val threadId = currentState.effectiveThreadId()
        val targetChatId = currentState.effectiveThreadChatId(chatId)
        if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) {
            require(parseMode == null) { "MTProto rich-text sending is not available" }
            require(entities.isEmpty()) { "MTProto entity sending is not available" }
            require(replyId == null && threadId == null) { "MTProto reply and topic sending is not available" }
            val chat = requireNotNull(chatListRepository.getChatById(targetChatId)) {
                "MTProto target chat is not projected"
            }
            val peer = TelegramPeerChatId.decode(targetChatId, chat.isChannel)
            mtProtoTextMessageRepository.sendText(
                chatId = targetChatId,
                peerType = peer.type,
                text = text,
                silent = sendOptions.silent,
                scheduleDate = sendOptions.scheduleDate,
                disableLinkPreview = sendOptions.disableLinkPreview,
            )
        } else if (parseMode == null) {
            repositoryMessage.sendMessage(
                targetChatId,
                text,
                replyId,
                entities,
                threadId,
                sendOptions
            )
        } else {
            repositoryMessage.sendRichMessage(
                chatId = targetChatId,
                markdown = text,
                replyToMsgId = replyId,
                threadId = threadId,
                sendOptions = sendOptions,
                isRtl = null,
                detectAutomaticBlocks = true,
                parseMode = parseMode
            )
        }
        onCancelReply()
        if (sendOptions.scheduleDate == null) {
            clearDraftLinkPreviewAfterSend()
        }
        if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
    if (!ensureTdLibCaptionLimit(caption)) return
    launchPendingAttachmentSend(
        operation = "photo",
        paths = listOf(photoPath),
        caption = caption,
        captionEntities = captionEntities,
        sendOptions = sendOptions
    ) {
        val removedAttachments = takeStagedAttachments(listOf(photoPath))
        try {
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
            if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
                onScrollToBottom()
            }
            if (sendOptions.scheduleDate != null) {
                loadScheduledMessages()
            }
            if (sendOptions.scheduleDate == null) {
                cleanupTempAttachments(removedAttachments.attachments)
            }
        } catch (e: Exception) {
            restoreStagedAttachments(removedAttachments)
            throw e
        }
    }
}

internal fun DefaultChatComponent.handleSendVideo(
    videoPath: String,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    if (!ensureTdLibCaptionLimit(caption)) return
    launchPendingAttachmentSend(
        operation = "video",
        paths = listOf(videoPath),
        caption = caption,
        captionEntities = captionEntities,
        sendOptions = sendOptions
    ) {
        val removedAttachments = takeStagedAttachments(listOf(videoPath))
        try {
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
            if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
                onScrollToBottom()
            }
            if (sendOptions.scheduleDate != null) {
                loadScheduledMessages()
            }
            if (sendOptions.scheduleDate == null) {
                cleanupTempAttachments(removedAttachments.attachments)
            }
        } catch (e: Exception) {
            restoreStagedAttachments(removedAttachments)
            throw e
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
        if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
    if (!ensureTdLibCaptionLimit(caption)) return
    launchPendingAttachmentSend(
        operation = "document",
        paths = listOf(path),
        caption = caption,
        captionEntities = captionEntities,
        sendOptions = sendOptions
    ) {
        val removedAttachments = takeStagedAttachments(listOf(path))
        try {
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
            if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
                onScrollToBottom()
            }
            if (sendOptions.scheduleDate != null) {
                loadScheduledMessages()
            }
            if (sendOptions.scheduleDate == null) {
                cleanupTempAttachments(removedAttachments.attachments)
            }
        } catch (e: Exception) {
            restoreStagedAttachments(removedAttachments)
            throw e
        }
    }
}

internal fun DefaultChatComponent.handleSendPoll(
    poll: PollDraft,
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    val limits = tdLibLimitsRepository.limits.value
    val pollAnswerCountMax = limits.pollAnswerCountMax
    if (pollAnswerCountMax != null && poll.options.size > pollAnswerCountMax) {
        toastMessageDisplayer.show("Poll has too many answers. Maximum is $pollAnswerCountMax")
        return
    }
    val pollOpenPeriodMax = limits.pollOpenPeriodMax
    if (pollOpenPeriodMax != null && poll.openPeriod > pollOpenPeriodMax) {
        toastMessageDisplayer.show("Poll open period exceeds $pollOpenPeriodMax seconds")
        return
    }
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
        if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
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
    if (!ensureTdLibCaptionLimit(caption)) return
    launchPendingAttachmentSend(
        operation = "gif_file",
        paths = listOf(path),
        caption = caption,
        captionEntities = captionEntities,
        sendOptions = sendOptions
    ) {
        val removedAttachments = takeStagedAttachments(listOf(path))
        try {
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
            if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
                onScrollToBottom()
            }
            if (sendOptions.scheduleDate != null) {
                loadScheduledMessages()
            }
            if (sendOptions.scheduleDate == null) {
                cleanupTempAttachments(removedAttachments.attachments)
            }
        } catch (e: Exception) {
            restoreStagedAttachments(removedAttachments)
            throw e
        }
    }
}

internal fun DefaultChatComponent.handleSendAlbum(
    paths: List<String>,
    caption: String,
    captionEntities: List<MessageEntity> = emptyList(),
    sendOptions: MessageSendOptions = MessageSendOptions()
) {
    if (!ensureTdLibCaptionLimit(caption)) return
    launchPendingAttachmentSend(
        operation = "album",
        paths = paths,
        caption = caption,
        captionEntities = captionEntities,
        sendOptions = sendOptions
    ) {
        val removedAttachments = takeStagedAttachments(paths)
        try {
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
            if (sendOptions.scheduleDate == null && shouldAutoScrollAfterSend(currentState.isAtBottom)) {
                onScrollToBottom()
            }
            if (sendOptions.scheduleDate != null) {
                loadScheduledMessages()
            }
            if (sendOptions.scheduleDate == null) {
                cleanupTempAttachments(removedAttachments.attachments)
            }
        } catch (e: Exception) {
            restoreStagedAttachments(removedAttachments)
            throw e
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
        val compressionProfile = resolvePhotoCompressionProfile(
            width = normalizedBitmap.width,
            height = normalizedBitmap.height
        )
        val bitmapForUpload = normalizedBitmap.scaleForCompression(compressionProfile)

        val compressedFile = File(
            cacheController.getCacheDir(),
            "compressed_photo_${System.currentTimeMillis()}.jpg"
        )
        FileOutputStream(compressedFile).use { out ->
            bitmapForUpload.compress(
                Bitmap.CompressFormat.JPEG,
                compressionProfile.quality,
                out
            )
        }
        if (bitmapForUpload !== normalizedBitmap && !bitmapForUpload.isRecycled) {
            bitmapForUpload.recycle()
        }
        if (!normalizedBitmap.isRecycled) {
            normalizedBitmap.recycle()
        }
        compressedFile.absolutePath
    } catch (_: Exception) {
        null
    }
}

internal fun resolvePhotoCompressionProfile(width: Int, height: Int): PhotoCompressionProfile {
    val safeWidth = width.coerceAtLeast(1)
    val safeHeight = height.coerceAtLeast(1)
    val longestSide = max(safeWidth, safeHeight)
    val resizeScale = if (longestSide > MaxCompressedPhotoLongSide) {
        MaxCompressedPhotoLongSide / longestSide.toFloat()
    } else {
        1f
    }
    val targetWidth = (safeWidth * resizeScale).roundToInt().coerceAtLeast(1)
    val targetHeight = (safeHeight * resizeScale).roundToInt().coerceAtLeast(1)
    val targetPixels = targetWidth.toLong() * targetHeight.toLong()
    val quality = when {
        targetPixels <= 2_000_000L -> 92
        targetPixels <= 4_000_000L -> 90
        targetPixels <= 8_000_000L -> 88
        else -> 85
    }

    return PhotoCompressionProfile(
        targetWidth = targetWidth,
        targetHeight = targetHeight,
        quality = quality
    )
}

private fun Bitmap.scaleForCompression(profile: PhotoCompressionProfile): Bitmap {
    if (width == profile.targetWidth && height == profile.targetHeight) return this
    return Bitmap.createScaledBitmap(this, profile.targetWidth, profile.targetHeight, true)
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
        val currentState = _state.value
        val username = currentState.preferredTelegramUsername()
        val inviteLink = currentState.preferredTelegramInviteLink()
        val link = when {
            username != null -> telegramLinkRepository.buildUrl(username)
            inviteLink != null -> telegramLinkRepository.rewriteTelegramLink(inviteLink)
            else -> chatOperationsRepository.getChatLink(chatId)?.let { fallback ->
                val fallbackPath = TelegramLinkDomains.extractPathAndQuery(fallback)
                if (fallbackPath != null) telegramLinkRepository.buildUrl(fallbackPath) else fallback
            }
        }
        if (!link.isNullOrBlank()) {
            localClipboard.nativeClipboard.setPrimaryClip(
                ClipData.newPlainText("", AnnotatedString(link))
            )
        }
    }
}

internal fun DefaultChatComponent.handleRepeatMessage(message: MessageModel) {
    scope.launch {
        val key = OutgoingMessageReducer.Key(message.chatId, message.id)
        val outgoingState = _state.value.outgoingMessageStates[key]
        if (backendModeRepository.backendMode.value == TelegramBackendMode.KOTLIN_MTPROTO) {
            require(outgoingState !is OutgoingMessageReducer.State.Failed || !outgoingState.retryable) {
                "MTProto retrying failed messages is not available"
            }
            val chat = requireNotNull(chatListRepository.getChatById(chatId)) {
                "MTProto target chat is not projected"
            }
            val peer = TelegramPeerChatId.decode(chatId, chat.isChannel)
            mtProtoTextMessageRepository.forwardToSelf(chatId, peer.type, message.id)
        } else if (outgoingState is OutgoingMessageReducer.State.Failed && outgoingState.retryable) {
            repositoryMessage.retryFailedMessage(message.chatId, message.id)
        } else {
            repositoryMessage.forwardMessage(chatId, chatId, message.id, sendCopy = true)
        }
    }
}
