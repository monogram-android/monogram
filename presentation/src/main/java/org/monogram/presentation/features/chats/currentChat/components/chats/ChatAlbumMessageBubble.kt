package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.DateFormatManager
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.CompactMediaMosaic

@Composable
fun ChatAlbumMessageBubble(
    messages: List<MessageModel>,
    isOutgoing: Boolean,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    autoDownloadMobile: Boolean = false,
    autoDownloadWifi: Boolean = false,
    autoDownloadRoaming: Boolean = false,
    autoDownloadFiles: Boolean = false,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
    onVideoClick: (MessageModel) -> Unit,
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    toProfile: (Long) -> Unit = {},
    fontSize: Float = 16f,
    letterSpacing: Float = 0f,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    if (messages.isEmpty()) return

    val uniqueMessages = remember(messages) { messages.distinct() }
    val isDocumentAlbum = remember(uniqueMessages) { uniqueMessages.all { it.content is MessageContent.Document } }
    val isAudioAlbum = remember(uniqueMessages) { uniqueMessages.all { it.content is MessageContent.Audio } }

    if (isDocumentAlbum) {
        DocumentAlbumBubble(
            messages = uniqueMessages,
            isOutgoing = isOutgoing,
            isSameSenderAbove = isSameSenderAbove,
            isSameSenderBelow = isSameSenderBelow,
            fontSize = fontSize,
            letterSpacing = letterSpacing,
            autoDownloadFiles = autoDownloadFiles,
            autoDownloadMobile = autoDownloadMobile,
            autoDownloadWifi = autoDownloadWifi,
            autoDownloadRoaming = autoDownloadRoaming,
            onDocumentClick = onDocumentClick,
            onCancelDownload = onCancelDownload,
            onLongClick = onLongClick,
            onReplyClick = onReplyClick,
            onReactionClick = onReactionClick,
            isGroup = isGroup,
            toProfile = toProfile,
            modifier = modifier,
            downloadUtils = downloadUtils
        )
        return
    }

    if (isAudioAlbum) {
        AudioAlbumBubble(
            messages = uniqueMessages,
            isOutgoing = isOutgoing,
            isSameSenderAbove = isSameSenderAbove,
            isSameSenderBelow = isSameSenderBelow,
            fontSize = fontSize,
            letterSpacing = letterSpacing,
            autoDownloadFiles = autoDownloadFiles,
            autoDownloadMobile = autoDownloadMobile,
            autoDownloadWifi = autoDownloadWifi,
            autoDownloadRoaming = autoDownloadRoaming,
            onAudioClick = onAudioClick,
            onCancelDownload = onCancelDownload,
            onLongClick = onLongClick,
            onReplyClick = onReplyClick,
            onReactionClick = onReactionClick,
            isGroup = isGroup,
            toProfile = toProfile,
            modifier = modifier,
            downloadUtils = downloadUtils
        )
        return
    }

    val cornerRadius = 16.dp
    val smallCorner = 4.dp

    val bubbleShape = remember(isOutgoing, isSameSenderAbove, isSameSenderBelow) {
        RoundedCornerShape(
            topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
            topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
            bottomStart = if (!isOutgoing) {
                if (isSameSenderBelow) smallCorner else cornerRadius
            } else cornerRadius,
            bottomEnd = if (isOutgoing) {
                if (isSameSenderBelow) smallCorner else cornerRadius
            } else cornerRadius
        )
    }

    val dateFormatManager: DateFormatManager = koinInject()
    val timeFormat = dateFormatManager.getHourMinuteFormat()

    val captionMsg = remember(uniqueMessages) {
        uniqueMessages.firstOrNull {
            val content = it.content
            (content is MessageContent.Photo && content.caption.isNotEmpty()) ||
                    (content is MessageContent.Video && content.caption.isNotEmpty()) ||
                    (content is MessageContent.Gif && content.caption.isNotEmpty())
        }
    }

    val caption = remember(captionMsg) {
        when (val content = captionMsg?.content) {
            is MessageContent.Photo -> content.caption
            is MessageContent.Video -> content.caption
            is MessageContent.Gif -> content.caption
            else -> ""
        }
    }

    val entities = remember(captionMsg) {
        when (val content = captionMsg?.content) {
            is MessageContent.Photo -> content.entities
            is MessageContent.Video -> content.entities
            is MessageContent.Gif -> content.entities
            else -> emptyList()
        }
    }

    val lastMsg = uniqueMessages.last()
    val formattedTime = remember(lastMsg.date) { formatTime(lastMsg.date, timeFormat) }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.clip(bubbleShape)
        ) {
            Column {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        MessageSenderName(lastMsg, toProfile = toProfile)
                    }
                }

                lastMsg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                    }
                }

                lastMsg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = isOutgoing,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                CompactMediaMosaic(
                    messages = uniqueMessages,
                    autoplayGifs = autoplayGifs,
                    autoplayVideos = autoplayVideos,
                    onPhotoClick = onPhotoClick,
                    onDownloadPhoto = onDownloadPhoto,
                    onVideoClick = onVideoClick,
                    onCancelDownload = onCancelDownload,
                    onLongClick = onLongClick,
                    showTimestampOverlay = caption.isEmpty(),
                    timestampStr = formattedTime,
                    isRead = lastMsg.isRead,
                    isOutgoing = isOutgoing,
                    isChannel = false,
                    views = lastMsg.views,
                    sendingState = lastMsg.sendingState,
                    autoDownloadMobile = autoDownloadMobile,
                    autoDownloadWifi = autoDownloadWifi,
                    autoDownloadRoaming = autoDownloadRoaming,
                    toProfile = toProfile,
                    downloadUtils = downloadUtils,
                    isAnyViewerOpen = isAnyViewerOpen
                )

                if (caption.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        val inlineContent = rememberMessageInlineContent(entities, fontSize)
                        val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                            text = caption,
                            entities = entities,
                            isOutgoing = isOutgoing,
                            revealedSpoilers = revealedSpoilers
                        )

                        MessageText(
                            text = finalAnnotatedString,
                            inlineContent = inlineContent,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                letterSpacing = letterSpacing.sp,
                                lineHeight = (fontSize * 1.375f).sp
                            ),
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            onSpoilerClick = { index ->
                                if (revealedSpoilers.contains(index)) {
                                    revealedSpoilers.remove(index)
                                } else {
                                    revealedSpoilers.add(index)
                                }
                            },
                            onClick = { offset -> onLongClick(bubblePosition + offset) },
                            onLongClick = { offset -> onLongClick(bubblePosition + offset) }
                        )

                        Box(modifier = Modifier.align(Alignment.End)) {
                            MessageMetadata(
                                msg = lastMsg,
                                isOutgoing = isOutgoing,
                                contentColor = if (isOutgoing) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                }
                            )
                        }
                    }
                }
            }
        }

        MessageReactionsView(
            reactions = lastMsg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun ChatTimestampInfo(
    time: String,
    isRead: Boolean,
    isOutgoing: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    sendingState: MessageSendingState? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color
        )
        if (isOutgoing) {
            Spacer(modifier = Modifier.width(4.dp))
            MessageSendingStatusIcon(
                sendingState = sendingState,
                isRead = isRead,
                baseColor = color,
                size = 14.dp
            )
        }
    }
}
