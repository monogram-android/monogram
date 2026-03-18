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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType
import org.monogram.presentation.features.chats.currentChat.components.chats.ForwardContent
import org.monogram.presentation.features.chats.currentChat.components.chats.MessageReactionsView
import org.monogram.presentation.features.chats.currentChat.components.chats.ReplyContent

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

    val hasPath = !content.path.isNullOrBlank()

    LaunchedEffect(content.path, content.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (!hasPath && !content.isDownloading) {
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
            Column {
                msg.forwardInfo?.let { forward ->
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        ForwardContent(forward, false, onForwardClick = toProfile)
                    }
                }
                msg.replyToMsg?.let { reply ->
                    Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                        ReplyContent(
                            replyToMsg = reply,
                            isOutgoing = false,
                            onClick = { onReplyClick(reply) }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp)
                        .aspectRatio(
                            if (content.width > 0 && content.height > 0)
                                content.width.toFloat() / content.height.toFloat()
                            else 1f
                        )
                        .onGloballyPositioned { gifPosition = it.positionInWindow() }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (content.isDownloading) {
                                        onCancelDownload(content.fileId)
                                    } else {
                                        onGifClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(gifPosition + offset) }
                            )
                        }
                ) {
                    Crossfade(
                        targetState = hasPath,
                        animationSpec = tween(300),
                        label = "GifLoading"
                    ) { targetHasPath ->
                        if (targetHasPath) {
                            if (autoplayGifs) {
                                content.path?.let { path ->
                                    VideoStickerPlayer(
                                        path = path,
                                        type = VideoType.Gif,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        animate = !isAnyViewerOpen,
                                        videoPlayerPool = videoPlayerPool
                                    )
                                }
                            } else {
                                Image(
                                    painter = rememberAsyncImagePainter(content.path),
                                    contentDescription = content.caption,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
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
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (content.minithumbnail != null) {
                                    Image(
                                        painter = rememberAsyncImagePainter(content.minithumbnail),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .blur(10.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (content.isDownloading) {
                                        CircularProgressIndicator(
                                            progress = { content.downloadProgress },
                                            modifier = Modifier.size(32.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        )
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            modifier = Modifier.size(20.dp),
                                            tint = Color.White
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download",
                                            modifier = Modifier.size(28.dp),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
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

                if (content.caption.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
                    ) {
                        Text(
                            text = content.caption,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.375f).sp
                            ),
                            modifier = Modifier.padding(bottom = 2.dp)
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
            Spacer(modifier = Modifier.height(4.dp))
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
                    .padding(horizontal = 4.dp)
                    .align(Alignment.Start)
            )
        }
    }
}
