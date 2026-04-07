package org.monogram.presentation.features.chats.currentChat.components.chats

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoMessageBubble(
    content: MessageContent.Video,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    letterSpacing: Float,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoplayVideos: Boolean,
    onVideoClick: (MessageModel) -> Unit,
    modifier: Modifier = Modifier,
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    onReplyClick: (MessageModel) -> Unit = {},
    onReactionClick: (String) -> Unit = {},
    showMetadata: Boolean = true,
    showReactions: Boolean = true,
    toProfile: (Long) -> Unit = {},
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val cornerRadius = 18.dp
    val smallCorner = 4.dp
    val tailCorner = 2.dp

    val context = LocalContext.current
    var stablePath by remember(msg.id, content.fileId) { mutableStateOf(content.path) }
    val hasPath = !stablePath.isNullOrBlank()
    val videoCacheKey = remember(stablePath, content.fileId) {
        namespacedCacheKey("chat_video:${content.fileId}", stablePath)
    }
    val videoMiniCacheKey = remember(content.minithumbnail, content.fileId) {
        content.minithumbnail?.let { namespacedCacheKey("chat_video_mini:${content.fileId}", it) }
    }
    var isAutoDownloadSuppressed by remember(msg.id, content.fileId) { mutableStateOf(false) }

    LaunchedEffect(content.path, content.fileId) {
        if (!content.path.isNullOrBlank()) {
            stablePath = content.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(content.fileId)
        } else {
            stablePath = null
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
            if (shouldDownload) {
                onVideoClick(msg)
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

    var videoPosition by remember { mutableStateOf(Offset.Zero) }
    var isMuted by remember { mutableStateOf(true) }
    var currentPositionSeconds by remember { mutableIntStateOf(0) }
    var isVisible by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val screenHeightPx = remember { resources.displayMetrics.heightPixels }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var isMediaSpoilerRevealed by remember { mutableStateOf(!content.hasSpoiler) }

    Column(
        modifier = modifier.onGloballyPositioned {
            val rect = it.boundsInWindow()
            isVisible = rect.bottom > 0 && rect.top < screenHeightPx
        },
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(modifier = Modifier
                .widthIn(max = 280.dp)
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

                val ratio = if (content.width > 0 && content.height > 0)
                    (content.width.toFloat() / content.height.toFloat()).coerceIn(0.5f, 2f)
                else 1f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 360.dp)
                        .aspectRatio(ratio)
                        .clipToBounds()
                        .onGloballyPositioned { videoPosition = it.positionInWindow() }
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
                                    onProgressUpdate = { pos ->
                                        val seconds = (pos / 1000).toInt()
                                        if (seconds != currentPositionSeconds) {
                                            currentPositionSeconds = seconds
                                        }
                                    },
                                    fileId = if (!hasPath && content.supportsStreaming) content.fileId else 0,
                                    thumbnailData = content.minithumbnail
                                )

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
                                        contentDescription = "Toggle Sound",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                if (hasPath) {
                                    Image(
                                        painter = rememberAsyncImagePainter(
                                            model = ImageRequest.Builder(context)
                                                .data(stablePath)
                                                .apply {
                                                    videoCacheKey?.let {
                                                        memoryCacheKey(it)
                                                        diskCacheKey(it)
                                                    }
                                                }
                                                .crossfade(true)
                                                .build()
                                        ),
                                        contentDescription = content.caption,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    if (content.minithumbnail != null) {
                                        Image(
                                            painter = rememberAsyncImagePainter(
                                                model = ImageRequest.Builder(context)
                                                    .data(content.minithumbnail)
                                                    .apply {
                                                        videoMiniCacheKey?.let {
                                                            memoryCacheKey(it)
                                                            diskCacheKey(it)
                                                        }
                                                    }
                                                    .build()
                                            ),
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
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            MediaLoadingBackground(
                                previewData = content.minithumbnail,
                                contentScale = ContentScale.Fit,
                                previewBlur = 14.dp
                            )

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f))
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

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if ((hasPath || content.supportsStreaming) && autoplayVideos) {
                                "${formatDuration(currentPositionSeconds)} / ${formatDuration(content.duration)}"
                            } else {
                                formatDuration(content.duration)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }

                    if (content.isUploading) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (content.uploadProgress > 0f) {
                                CircularWavyProgressIndicator(
                                    progress = { content.uploadProgress },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.3f)
                                )
                            } else {
                                LoadingIndicator(
                                    color = Color.White
                                )
                            }
                        }
                    }

                    SpoilerWrapper(isRevealed = isMediaSpoilerRevealed) {
                        Box(modifier = Modifier.fillMaxSize())
                    }

                    if (content.caption.isEmpty() && showMetadata) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            MessageMetadata(msg, isOutgoing, Color.White)
                        }
                    }
                }

                if (content.caption.isNotEmpty()) {
                    val timeColor = if (isOutgoing)
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

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
                            modifier = Modifier.padding(bottom = 4.dp),
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
                            Box(modifier = Modifier.align(Alignment.End)) {
                                MessageMetadata(msg, isOutgoing, timeColor)
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
