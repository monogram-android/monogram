package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.channels.ChannelCommentsButton

@Composable
fun DocumentMessageBubble(
    content: MessageContent.Document,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    autoDownloadFiles: Boolean,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onDocumentClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onClick: (Offset) -> Unit = {},
    isGroup: Boolean = false,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp

    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(
        content.path,
        content.isDownloading,
        autoDownloadFiles,
        autoDownloadMobile,
        autoDownloadWifi,
        autoDownloadRoaming
    ) {
        if (!content.path.isNullOrBlank()) {
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(content.fileId)
        }

        val shouldDownload = if (autoDownloadFiles) {
            when {
                downloadUtils.isRoaming() -> autoDownloadRoaming
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                else -> autoDownloadMobile
            }
        } else {
            false
        }

        if (shouldDownload && content.path == null && !content.isDownloading && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                content.fileId
            )
        ) {
            onDocumentClick(msg)
        }
    }

    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius,
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius
    )

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier
            .onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 184.dp, max = 300.dp)
            ) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(msg, toProfile = toProfile)
                }

                msg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = isOutgoing,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                DocumentRow(
                    content = content,
                    msg = msg,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    timeColor = timeColor,
                    onDocumentClick = {
                        isAutoDownloadSuppressed = false
                        AutoDownloadSuppression.clear(content.fileId)
                        onDocumentClick(it)
                    },
                    onCancelDownload = {
                        isAutoDownloadSuppressed = true
                        AutoDownloadSuppression.suppress(content.fileId)
                        onCancelDownload(it)
                    }
                )

                if (content.caption.isNotEmpty()) {
                    val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                    val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                        text = content.caption,
                        entities = content.entities,
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
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        entities = content.entities,
                        onSpoilerClick = { index ->
                            if (revealedSpoilers.contains(index)) {
                                revealedSpoilers.remove(index)
                            } else {
                                revealedSpoilers.add(index)
                            }
                        },
                        onClick = { offset -> onClick(offset) },
                        onLongClick = { offset -> onLongClick(offset) }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (content.caption.isEmpty()) 0.dp else 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    MessageMetadata(msg, isOutgoing, timeColor)
                }
            }
        }

        MessageReactionsView(
            reactions = msg.reactions,
            onReactionClick = onReactionClick,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun DocumentRow(
    content: MessageContent.Document,
    msg: MessageModel,
    fontSize: Float,
    letterSpacing: Float,
    timeColor: Color,
    onDocumentClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    if (content.isDownloading) {
                        AutoDownloadSuppression.suppress(content.fileId)
                        onCancelDownload(content.fileId)
                    } else {
                        AutoDownloadSuppression.clear(content.fileId)
                        onDocumentClick(msg)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (content.isDownloading || content.isUploading) {
                CircularProgressIndicator(
                    progress = { if (content.isDownloading) content.downloadProgress else content.uploadProgress },
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp,
                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cancel",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                val icon =
                    if (content.path == null) Icons.Default.Download else Icons.AutoMirrored.Filled.InsertDriveFile
                Icon(
                    imageVector = icon,
                    contentDescription = "File",
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = content.fileName.ifEmpty { "Document" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = fontSize.sp,
                    letterSpacing = letterSpacing.sp,
                ),
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

@Composable
fun DocumentAlbumBubble(
    messages: List<MessageModel>,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    autoDownloadFiles: Boolean,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    onDocumentClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit,
    onReactionClick: (String) -> Unit,
    isGroup: Boolean = false,
    toProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils
) {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius,
        bottomStart = if (!isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius,
        bottomEnd = if (isOutgoing) {
            if (isSameSenderBelow) smallCorner else tailCorner
        } else cornerRadius
    )

    val backgroundColor =
        if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor =
        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val timeColor = contentColor.copy(alpha = 0.7f)

    val lastMsg = messages.last()
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var bubblePosition by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.onGloballyPositioned { bubblePosition = it.positionInWindow() },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 200.dp, max = 300.dp)
            ) {
                if (isGroup && !isOutgoing && !isSameSenderAbove) {
                    MessageSenderName(lastMsg, toProfile = toProfile)
                }

                lastMsg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = isOutgoing,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                messages.forEachIndexed { index, msg ->
                    val content = msg.content as MessageContent.Document

                    DocumentRow(
                        content = content,
                        msg = msg,
                        fontSize = fontSize,
                        letterSpacing = letterSpacing,
                        timeColor = timeColor,
                        onDocumentClick = onDocumentClick,
                        onCancelDownload = onCancelDownload
                    )
                    if (index < messages.lastIndex || content.caption.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                val captionMsg = messages.firstOrNull { (it.content as MessageContent.Document).caption.isNotEmpty() }
                if (captionMsg != null) {
                    val content = captionMsg.content as MessageContent.Document
                    val inlineContent = rememberMessageInlineContent(content.entities, fontSize)
                    val finalAnnotatedString = buildAnnotatedMessageTextWithEmoji(
                        text = content.caption,
                        entities = content.entities,
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
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
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
                    MessageMetadata(lastMsg, isOutgoing, timeColor)
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
fun ChannelDocumentAlbumBubble(
    messages: List<MessageModel>,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
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
        bottomEnd = cornerRadius
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
                    DocumentRow(
                        content = content,
                        msg = msg,
                        fontSize = fontSize,
                        letterSpacing = letterSpacing,
                        timeColor = timeColor,
                        onDocumentClick = onDocumentClick,
                        onCancelDownload = onCancelDownload
                    )
                    if (index < messages.lastIndex || content.caption.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
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
                            letterSpacing = letterSpacing.sp,
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
            Spacer(modifier = Modifier.height(4.dp))
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
