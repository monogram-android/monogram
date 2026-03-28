package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.rememberAsyncImagePainter
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelGifMessageBubble(
    content: MessageContent.Gif,
    msg: MessageModel,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    fontSize: Float,
    bubbleRadius: Float = 18f,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoplayGifs: Boolean,
    onGifClick: (MessageModel) -> Unit = {},
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    onCommentsClick: (Long) -> Unit = {},
    showComments: Boolean = true,
    showMetadata: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    downloadUtils: IDownloadUtils,
    videoPlayerPool: VideoPlayerPool,
    isAnyViewerOpen: Boolean = false
) {
    val context = LocalContext.current
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val bubbleShape = RoundedCornerShape(
        topStart = if (isSameSenderAbove) smallCorner else cornerRadius,
        topEnd = cornerRadius,
        bottomStart = if (isSameSenderBelow) smallCorner else tailCorner,
        bottomEnd = cornerRadius
    )

    var gifPosition by remember { mutableStateOf(Offset.Zero) }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }

    var stablePath by remember(msg.id) { mutableStateOf(content.path) }
    val hasPath = !stablePath.isNullOrBlank()
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.animateContentSize()) {
                msg.forwardInfo?.let { forward ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                            .zIndex(1f)
                    ) {
                        ForwardContent(forward, false, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                            .zIndex(1f)
                    ) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = false,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                val mediaRatio = if (content.width > 0 && content.height > 0)
                    (content.width.toFloat() / content.height.toFloat()).coerceIn(0.5f, 2f)
                else 1f

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val mediaHeight = (maxWidth / mediaRatio).coerceIn(160.dp, 320.dp)

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(mediaHeight)
                            .clipToBounds()
                            .onGloballyPositioned { gifPosition = it.positionInWindow() }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (content.isDownloading) {
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
                        if (hasPath) {
                            if (autoplayGifs) {
                                stablePath?.let { path ->
                                    VideoStickerPlayer(
                                        path = path,
                                        type = VideoType.Gif,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit,
                                        animate = !isAnyViewerOpen,
                                        videoPlayerPool = videoPlayerPool,
                                        thumbnailData = content.minithumbnail
                                    )
                                }
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

                        if (content.caption.isEmpty() && (hasPath || msg.isOutgoing || content.minithumbnail != null) && showMetadata) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    msg.views?.let { viewsCount ->
                                        if (viewsCount > 0) {
                                            Icon(
                                                imageVector = Icons.Outlined.Visibility,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = Color.White
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = formatViews(context, viewsCount),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = Color.White
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                    }
                                    Text(
                                        text = formatTime(context, msg.date),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = Color.White
                                    )
                                    if (msg.isOutgoing) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        AnimatedContent(
                                            targetState = msg.sendingState to msg.isRead,
                                            transitionSpec = {
                                                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(
                                                    animationSpec = tween(
                                                        300
                                                    )
                                                )
                                            },
                                            label = "SendingState"
                                        ) { (sendingState, isRead) ->
                                            val statusIcon = when (sendingState) {
                                                is MessageSendingState.Pending -> Icons.Default.Schedule
                                                is MessageSendingState.Failed -> Icons.Default.Error
                                                null -> if (isRead) Icons.Default.DoneAll else Icons.Default.Check
                                            }
                                            Icon(
                                                imageVector = statusIcon,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = if (sendingState is MessageSendingState.Failed) Color.Red else Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (content.caption.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                            .zIndex(1f)
                    ) {
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
                                msg.views?.let { viewsCount ->
                                    if (viewsCount > 0) {
                                        Icon(
                                            imageVector = Icons.Outlined.Visibility,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = formatViews(context, viewsCount),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                }
                                Text(
                                    text = formatTime(context, msg.date),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                                )
                                if (msg.isOutgoing) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    AnimatedContent(
                                        targetState = msg.sendingState to msg.isRead,
                                        transitionSpec = {
                                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(
                                                animationSpec = tween(
                                                    300
                                                )
                                            )
                                        },
                                        label = "SendingState"
                                    ) { (sendingState, isRead) ->
                                        val statusIcon = when (sendingState) {
                                            is MessageSendingState.Pending -> Icons.Default.Schedule
                                            is MessageSendingState.Failed -> Icons.Default.Error
                                            null -> if (isRead) Icons.Default.DoneAll else Icons.Default.Check
                                        }
                                        Icon(
                                            imageVector = statusIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = if (sendingState is MessageSendingState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                0.6f
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showComments && msg.canGetMessageThread) {
            ChannelCommentsButton(
                replyCount = msg.replyCount,
                bubbleRadius = bubbleRadius,
                isSameSenderBelow = isSameSenderBelow,
                onClick = { onCommentsClick(msg.id) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Reactions
        if (showReactions) {
            MessageReactionsView(
                reactions = msg.reactions,
                onReactionClick = onReactionClick,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .align(Alignment.Start)
            )
        }
    }
}
