package org.monogram.presentation.features.chats.currentChat.components.channels

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.rememberAsyncImagePainter
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType
import org.monogram.presentation.features.chats.currentChat.components.chats.*

@Composable
fun ChannelVideoMessageBubble(
    content: MessageContent.Video,
    msg: MessageModel,
    isSameSenderAbove: Boolean = false,
    isSameSenderBelow: Boolean = false,
    fontSize: Float,
    letterSpacing: Float,
    bubbleRadius: Float = 18f,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoplayVideos: Boolean,
    onVideoClick: (MessageModel) -> Unit,
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
    val cornerRadius = bubbleRadius.dp
    val smallCorner = (bubbleRadius / 4f).coerceAtLeast(4f).dp
    val tailCorner = 2.dp

    val topStart = if (isSameSenderAbove) smallCorner else cornerRadius
    val topEnd = cornerRadius
    val bottomStart = if (isSameSenderBelow) smallCorner else tailCorner
    val bottomEnd = cornerRadius

    val bubbleShape = RoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomStart = bottomStart,
        bottomEnd = bottomEnd
    )

    var videoPosition by remember { mutableStateOf(Offset.Zero) }
    var isMuted by remember { mutableStateOf(true) }
    var currentPositionSeconds by remember { mutableIntStateOf(0) }
    var isVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val screenHeightPx = remember { context.resources.displayMetrics.heightPixels }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }

    var stablePath by remember(msg.id) { mutableStateOf(content.path) }
    val hasPath = !stablePath.isNullOrBlank()
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }
    val hasCaption = content.caption.isNotEmpty()

    LaunchedEffect(content.path) {
        if (!content.path.isNullOrBlank()) {
            stablePath = content.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(content.fileId)
        }
    }

    LaunchedEffect(content.path, content.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (content.path.isNullOrBlank() && !content.isDownloading && !content.supportsStreaming && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                content.fileId
            )
        ) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onVideoClick(msg)
        }
    }

    Column(
        modifier = modifier
            .onGloballyPositioned {
                val rect = it.boundsInWindow()
                isVisible = rect.bottom > 0 && rect.top < screenHeightPx
            },
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()) {
                // Headers
                if (msg.forwardInfo != null || msg.replyToMsg != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .zIndex(1f)
                    ) {
                        msg.forwardInfo?.let { ForwardContent(it, false, onForwardClick = toProfile) }
                        msg.replyToMsg?.let { ReplyContent(it, false, onClick = { onReplyClick(it) }) }
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
                            .clip(
                                if (hasCaption) RoundedCornerShape(
                                    topStart = topStart,
                                    topEnd = topEnd
                                ) else bubbleShape
                            )
                            .clipToBounds()
                            .onGloballyPositioned { videoPosition = it.positionInWindow() }
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
                                            onVideoClick(msg)
                                        }
                                    },
                                    onLongPress = { offset -> onLongClick(videoPosition + offset) }
                                )
                            }
                    ) {
                        if (hasPath || content.supportsStreaming) {
                            if (autoplayVideos) {
                                val videoPath = stablePath ?: "http://streaming/${content.fileId}"
                                VideoStickerPlayer(
                                    path = videoPath,
                                    type = VideoType.Gif,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    animate = isVisible && !isAnyViewerOpen,
                                    volume = if (isMuted) 0f else 1f,
                                    reportProgress = true,
                                    onProgressUpdate = {
                                        val seconds = (it / 1000).toInt()
                                        if (seconds != currentPositionSeconds) {
                                            currentPositionSeconds = seconds
                                        }
                                    },
                                    videoPlayerPool = videoPlayerPool,
                                    fileId = if (!hasPath && content.supportsStreaming) content.fileId else 0,
                                    thumbnailData = content.minithumbnail
                                )

                                // Volume Toggle
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(30.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                        .clickable { isMuted = !isMuted },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                if (hasPath) {
                                    Image(
                                        painter = rememberAsyncImagePainter(stablePath),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    if (content.minithumbnail != null) {
                                        Image(
                                            painter = rememberAsyncImagePainter(content.minithumbnail),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .blur(10.dp),
                                            contentScale = ContentScale.Fit
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        null,
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            // Duration Tag
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if ((hasPath || content.supportsStreaming) && autoplayVideos) {
                                        "${formatDuration(context, currentPositionSeconds)} / ${formatDuration(context, content.duration)}"
                                    } else {
                                        formatDuration(context, content.duration)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        } else {
                            // Placeholder / Download State
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
                                    idleIcon = if (content.supportsStreaming) Icons.Rounded.Stream else Icons.Default.Download,
                                    idleContentDescription = if (content.supportsStreaming) "Stream" else "Download",
                                    onCancelClick = {
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(content.fileId)
                                        onCancelDownload(content.fileId)
                                    },
                                    onIdleClick = {
                                        isAutoDownloadSuppressed = false
                                        AutoDownloadSuppression.clear(content.fileId)
                                        onVideoClick(msg)
                                    }
                                )
                            }
                    }

                        if (!hasCaption && showMetadata) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                MessageMetadata(msg, msg.isOutgoing, Color.White)
                            }
                        }
                    }
                }

                // Caption Section
                if (hasCaption) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)
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
                                letterSpacing = letterSpacing.sp,
                                lineHeight = (fontSize * 1.35f).sp
                            ),
                            onSpoilerClick = { index ->
                                if (revealedSpoilers.contains(index)) {
                                    revealedSpoilers.remove(index)
                                } else {
                                    revealedSpoilers.add(index)
                                }
                            },
                            onClick = { offset -> onLongClick(videoPosition + offset) },
                            onLongClick = { offset -> onLongClick(videoPosition + offset) }
                        )

                        if (showMetadata) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                MessageMetadata(
                                    msg = msg,
                                    isOutgoing = msg.isOutgoing,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
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
