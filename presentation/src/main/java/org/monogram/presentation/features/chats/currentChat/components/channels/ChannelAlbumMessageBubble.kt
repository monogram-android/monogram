package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.CompactMediaMosaic
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelAlbumMessageBubble(
    messages: List<MessageModel>,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
    modifier: Modifier = Modifier,
    fontSize: Float,
    onPhotoClick: (MessageModel) -> Unit,
    onLongClick: (Offset) -> Unit,
    downloadUtils: IDownloadUtils,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    autoDownloadMobile: Boolean = false,
    autoDownloadWifi: Boolean = false,
    autoDownloadRoaming: Boolean = false,
    autoDownloadFiles: Boolean = false,
    bubbleRadius: Float = 16f,
    onDownloadPhoto: (Int) -> Unit = {},
    onVideoClick: (MessageModel) -> Unit,
    onDocumentClick: (MessageModel) -> Unit = {},
    onAudioClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    toProfile: (Long) -> Unit = {},
    isAnyViewerOpen: Boolean = false
) {
    if (messages.isEmpty()) return

    val context = LocalContext.current
    val uniqueMessages = remember(messages) { messages.distinct() }
    val isDocumentAlbum = remember(uniqueMessages) { uniqueMessages.all { it.content is MessageContent.Document } }
    val isAudioAlbum = remember(uniqueMessages) { uniqueMessages.all { it.content is MessageContent.Audio } }

    if (isDocumentAlbum) {
        ChannelDocumentAlbumBubble(
            messages = uniqueMessages,
            isSameSenderAbove = isSameSenderAbove,
            isSameSenderBelow = isSameSenderBelow,
            fontSize = fontSize,
            bubbleRadius = bubbleRadius,
            autoDownloadFiles = autoDownloadFiles,
            autoDownloadMobile = autoDownloadMobile,
            autoDownloadWifi = autoDownloadWifi,
            autoDownloadRoaming = autoDownloadRoaming,
            onDocumentClick = onDocumentClick,
            onCancelDownload = onCancelDownload,
            onLongClick = onLongClick,
            onReplyClick = onReplyClick,
            onReactionClick = onReactionClick,
            onCommentsClick = onCommentsClick,
            showComments = showComments,
            toProfile = toProfile,
            modifier = modifier,
            downloadUtils = downloadUtils
        )
        return
    }

    if (isAudioAlbum) {
        ChannelAudioAlbumBubble(
            messages = uniqueMessages,
            isSameSenderAbove = isSameSenderAbove,
            isSameSenderBelow = isSameSenderBelow,
            fontSize = fontSize,
            bubbleRadius = bubbleRadius,
            autoDownloadFiles = autoDownloadFiles,
            autoDownloadMobile = autoDownloadMobile,
            autoDownloadWifi = autoDownloadWifi,
            autoDownloadRoaming = autoDownloadRoaming,
            onAudioClick = onAudioClick,
            onCancelDownload = onCancelDownload,
            onLongClick = onLongClick,
            onReplyClick = onReplyClick,
            onReactionClick = onReactionClick,
            onCommentsClick = onCommentsClick,
            showComments = showComments,
            toProfile = toProfile,
            modifier = modifier
        )
        return
    }

    val firstMsg = uniqueMessages.first()
    val lastMsg = uniqueMessages.last()

    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = cornerRadius,
        bottomStart = if (isSameSenderBelow) smallCorner else tailCorner,
        bottomEnd = if (showComments && firstMsg.canGetMessageThread) 4.dp else cornerRadius
    )

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

    val formattedTime = remember(lastMsg.date) { formatTime(context, lastMsg.date) }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = bubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .clip(bubbleShape)
        ) {
            Column {
                lastMsg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        ForwardContent(forward, false, onForwardClick = toProfile)
                    }
                }
                lastMsg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = false,
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
                    isOutgoing = lastMsg.isOutgoing,
                    isChannel = true,
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
                            isOutgoing = false,
                            revealedSpoilers = revealedSpoilers
                        )

                        MessageText(
                            text = finalAnnotatedString,
                            inlineContent = inlineContent,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.375f).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
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

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            MessageMetadata(
                                msg = lastMsg,
                                isOutgoing = lastMsg.isOutgoing,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        if (showComments && firstMsg.canGetMessageThread) {
            ChannelCommentsButton(
                replyCount = firstMsg.replyCount,
                bubbleRadius = bubbleRadius,
                isSameSenderBelow = isSameSenderBelow,
                onClick = { onCommentsClick(firstMsg.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        MessageReactionsView(
            reactions = lastMsg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier
                .padding(top = 2.dp)
                .align(Alignment.Start)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelDocumentAlbumBubble(
    messages: List<MessageModel>,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    bubbleRadius: Float,
    autoDownloadFiles: Boolean,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onDocumentClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (String) -> Unit,
    onCommentsClick: (Long) -> Unit,
    showComments: Boolean,
    toProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val firstMsg = messages.first()
    val lastMsg = messages.last()

    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = cornerRadius,
        bottomStart = if (isSameSenderBelow) smallCorner else tailCorner,
        bottomEnd = if (showComments && firstMsg.canGetMessageThread) 4.dp else cornerRadius
    )

    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
            ) {
                lastMsg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ForwardContent(forward, false, onForwardClick = toProfile)
                    }
                }
                lastMsg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = false,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                messages.forEachIndexed { index, msg ->
                    val content = msg.content as MessageContent.Document
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (index == messages.lastIndex && content.caption.isEmpty()) 0.dp else 8.dp)
                            .clickable { onDocumentClick(msg) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    if (content.isDownloading) {
                                        onCancelDownload(content.fileId)
                                    } else {
                                        onDocumentClick(msg)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (content.isDownloading || content.isUploading) {
                                CircularWavyProgressIndicator(
                                    progress = { if (content.isDownloading) content.downloadProgress else content.uploadProgress },
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                val icon =
                                    if (content.path == null) Icons.Default.Download else Icons.AutoMirrored.Filled.InsertDriveFile
                                Icon(
                                    imageVector = icon,
                                    contentDescription = "File",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = content.fileName.ifEmpty { "Document" },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatFileSize(content.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = timeColor
                            )
                        }
                    }
                }

                val captionMsg = messages.firstOrNull { (it.content as MessageContent.Document).caption.isNotEmpty() }
                if (captionMsg != null) {
                    val content = captionMsg.content as MessageContent.Document
                    val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                    val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                        text = content.caption,
                        entities = content.entities,
                        isOutgoing = false,
                        revealedSpoilers = revealedSpoilers
                    )

                    MessageText(
                        text = finalAnnotatedString,
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.375f).sp
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        entities = content.entities,
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
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    MessageMetadata(lastMsg, false, timeColor)
                }
            }
        }

        if (showComments && firstMsg.canGetMessageThread) {
            ChannelCommentsButton(
                replyCount = firstMsg.replyCount,
                bubbleRadius = bubbleRadius,
                isSameSenderBelow = isSameSenderBelow,
                onClick = { onCommentsClick(firstMsg.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        MessageReactionsView(
            reactions = firstMsg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelAudioAlbumBubble(
    messages: List<MessageModel>,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    bubbleRadius: Float,
    autoDownloadFiles: Boolean,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onAudioClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (String) -> Unit,
    onCommentsClick: (Long) -> Unit,
    showComments: Boolean,
    toProfile: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstMsg = messages.first()
    val lastMsg = messages.last()

    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = cornerRadius,
        bottomStart = if (isSameSenderBelow) smallCorner else tailCorner,
        bottomEnd = if (showComments && firstMsg.canGetMessageThread) 4.dp else cornerRadius
    )

    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                    .fillMaxWidth()
            ) {
                lastMsg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ForwardContent(forward, false, onForwardClick = toProfile)
                    }
                }
                lastMsg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(bottom = 8.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = false,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                messages.forEachIndexed { index, msg ->
                    val content = msg.content as MessageContent.Audio
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (index == messages.lastIndex && content.caption.isEmpty()) 0.dp else 8.dp)
                            .clickable { onAudioClick(msg) }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    if (content.isDownloading) {
                                        onCancelDownload(content.fileId)
                                    } else {
                                        onAudioClick(msg)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (content.isDownloading || content.isUploading) {
                                CircularWavyProgressIndicator(
                                    progress = { if (content.isDownloading) content.downloadProgress else content.uploadProgress },
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                val icon = if (content.path == null) Icons.Default.Download else Icons.Default.PlayArrow
                                Icon(
                                    imageVector = icon,
                                    contentDescription = "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = content.title.ifEmpty { content.fileName.ifEmpty { "Audio" } },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = fontSize.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = content.performer.ifEmpty { formatFileSize(content.size) },
                                style = MaterialTheme.typography.labelSmall,
                                color = timeColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val captionMsg = messages.firstOrNull { (it.content as MessageContent.Audio).caption.isNotEmpty() }
                if (captionMsg != null) {
                    val content = captionMsg.content as MessageContent.Audio
                    val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                    val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                        text = content.caption,
                        entities = content.entities,
                        isOutgoing = false,
                        revealedSpoilers = revealedSpoilers
                    )

                    MessageText(
                        text = finalAnnotatedString,
                        inlineContent = inlineContent,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.375f).sp
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        entities = content.entities,
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
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    MessageMetadata(lastMsg, false, timeColor)
                }
            }
        }

        if (showComments && firstMsg.canGetMessageThread) {
            ChannelCommentsButton(
                replyCount = firstMsg.replyCount,
                bubbleRadius = bubbleRadius,
                isSameSenderBelow = isSameSenderBelow,
                onClick = { onCommentsClick(firstMsg.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        MessageReactionsView(
            reactions = firstMsg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
