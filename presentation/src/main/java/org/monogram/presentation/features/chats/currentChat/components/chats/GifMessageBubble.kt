package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import coil3.compose.rememberAsyncImagePainter
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType

@OptIn(UnstableApi::class)
@Composable
fun GifMessageBubble(
    content: MessageContent.Gif,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoplayGifs: Boolean,
    onGifClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    showMetadata: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils,
    videoPlayerPool: VideoPlayerPool,
    isAnyViewerOpen: Boolean = false
) {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp

    var stablePath by remember(msg.id) { mutableStateOf(content.path) }
    !stablePath.isNullOrBlank()
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(content.path) {
        if (!content.path.isNullOrBlank()) {
            stablePath = content.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(content.fileId)
        }
    }

    LaunchedEffect(content.path, content.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (content.path.isNullOrBlank() && !content.isDownloading && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                content.fileId
            )
        ) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) {
                onGifClick(msg)
            }
        }
    }

    val topStart = if (!isOutgoing && isSameSenderAbove) smallCorner else cornerRadius
    val topEnd = if (isOutgoing && isSameSenderAbove) smallCorner else cornerRadius
    val bottomStart = if (!isOutgoing) {
        if (isSameSenderBelow) smallCorner else tailCorner
    } else cornerRadius
    val bottomEnd = if (isOutgoing) {
        if (isSameSenderBelow) smallCorner else tailCorner
    } else cornerRadius

    val bubbleShape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomEnd = bottomEnd,
        bottomStart = bottomStart
    )

    var gifPosition by remember { mutableStateOf(Offset.Zero) }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var isMediaSpoilerRevealed by remember { mutableStateOf(!content.hasSpoiler) }

    Column(
        modifier = modifier.width(IntrinsicSize.Max),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(modifier = Modifier
                .widthIn(min = 160.dp, max = 320.dp)
                .animateContentSize()) {
                msg.forwardInfo?.let { forward ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .zIndex(1f)
                    ) {
                        ForwardContent(forward, isOutgoing, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .zIndex(1f)
                    ) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = isOutgoing,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp)
                        .aspectRatio(
                            if (content.width > 0 && content.height > 0)
                                (content.width.toFloat() / content.height.toFloat()).coerceIn(0.5f, 2f)
                            else 1f
                        )
                        .clipToBounds()
                        .onGloballyPositioned { gifPosition = it.positionInWindow() }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isMediaSpoilerRevealed) {
                                        isMediaSpoilerRevealed = true
                                    } else if (content.isDownloading) {
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(content.fileId)
                                        onCancelDownload(content.fileId)
                                    } else {
                                        isAutoDownloadSuppressed = false
                                        AutoDownloadSuppression.clear(content.fileId)
                                        onGifClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(gifPosition + offset) }
                            )
                        }
                ) {
                    if (!stablePath.isNullOrBlank()) {
                            if (autoplayGifs) {
                                VideoStickerPlayer(
                                    path = stablePath ?: "",
                                    type = VideoType.Gif,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    animate = !isAnyViewerOpen,
                                    videoPlayerPool = videoPlayerPool,
                                    thumbnailData = content.minithumbnail
                                )
                            } else {
                                Image(
                                    painter = rememberAsyncImagePainter(stablePath),
                                    contentDescription = content.caption,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "GIF",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            MediaLoadingBackground(
                                previewData = content.minithumbnail,
                                contentScale = ContentScale.Fit
                            )

                            MediaLoadingAction(
                                isDownloading = content.isDownloading,
                                progress = content.downloadProgress,
                                idleIcon = Icons.Default.Download,
                                idleContentDescription = "Download",
                                onCancelClick = {
                                    isAutoDownloadSuppressed = true
                                    AutoDownloadSuppression.suppress(content.fileId)
                                    onCancelDownload(content.fileId)
                                },
                                onIdleClick = {
                                    isAutoDownloadSuppressed = false
                                    AutoDownloadSuppression.clear(content.fileId)
                                    onGifClick(msg)
                                }
                            )
                        }
                    }

                    if (content.isUploading) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { content.uploadProgress },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f),
                            )
                        }
                    }

                    SpoilerWrapper(isRevealed = isMediaSpoilerRevealed) {
                        Box(modifier = Modifier.fillMaxSize())
                    }

                    if (content.caption.isEmpty() && (!content.path.isNullOrBlank() || content.isUploading || content.minithumbnail != null) && showMetadata) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (msg.editDate > 0) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edited",
                                        modifier = Modifier.size(12.dp),
                                        tint = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = formatTime(msg.date),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color.White
                                )
                                if (isOutgoing) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    MessageSendingStatusIcon(
                                        sendingState = msg.sendingState,
                                        isRead = msg.isRead,
                                        baseColor = Color.White,
                                        size = 12.dp,
                                        usePrimaryForRead = false
                                    )
                                }
                            }
                        }
                    }
                }

                if (content.caption.isNotEmpty()) {
                    val timeColor =
                        if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            0.7f
                        )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
                            .zIndex(1f)
                    ) {
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
                            modifier = Modifier.padding(bottom = 2.dp),
                            onSpoilerClick = { index ->
                                if (revealedSpoilers.contains(index)) {
                                    revealedSpoilers.remove(index)
                                } else {
                                    revealedSpoilers.add(index)
                                }
                            },
                            onClick = { offset -> onLongClick(gifPosition + offset) },
                            onLongClick = { offset -> onLongClick(gifPosition + offset) }
                        )
                        if (showMetadata) {
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (msg.editDate > 0) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edited",
                                        modifier = Modifier.size(14.dp),
                                        tint = timeColor
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = formatTime(msg.date),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = timeColor
                                )
                                if (isOutgoing) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    MessageSendingStatusIcon(
                                        sendingState = msg.sendingState,
                                        isRead = msg.isRead,
                                        baseColor = timeColor,
                                        size = 14.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showReactions) {
            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
