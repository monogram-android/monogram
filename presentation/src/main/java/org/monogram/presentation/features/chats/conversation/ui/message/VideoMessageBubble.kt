package org.monogram.presentation.features.chats.conversation.ui.message

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.ForwardInfo
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.R
import org.monogram.presentation.core.media.VideoStickerPlayer
import org.monogram.presentation.core.media.VideoType
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.chats.conversation.AutoDownloadSuppression

private class VideoBubbleLayoutTracker {
    var videoPosition: Offset = Offset.Zero
}

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
    onForwardOriginClick: (ForwardInfo) -> Unit = {},
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

    val layoutTracker = remember { VideoBubbleLayoutTracker() }
    var isMuted by remember { mutableStateOf(true) }
    var isVisible by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val screenHeightPx = remember { resources.displayMetrics.heightPixels }
    val revealedSpoilers = remember { mutableStateListOf<Int>() }
    var isMediaSpoilerRevealed by remember { mutableStateOf(!content.hasSpoiler) }
    val currentPositionSecondsState = remember(msg.id, content.fileId) { mutableIntStateOf(0) }
    val currentPositionSeconds = currentPositionSecondsState.intValue
    val onLongClickState by rememberUpdatedState(onLongClick)
    val onVideoClickState by rememberUpdatedState(onVideoClick)
    val onCancelDownloadState by rememberUpdatedState(onCancelDownload)

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
            ) {
                msg.forwardInfo?.let { forward ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isOutgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .zIndex(1f)
                    ) {
                        ForwardContent(forward, isOutgoing, onForwardClick = onForwardOriginClick)
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
                        .onGloballyPositioned {
                            layoutTracker.videoPosition = it.positionInWindow()
                        }

                ) {
                    if (hasPath || content.supportsStreaming) {
                            if (autoplayVideos) {
                                val videoPath = stablePath ?: "http://streaming/${content.fileId}"
                                VideoStickerPlayer(
                                    path = videoPath,
                                    type = VideoType.Gif,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    animate = isVisible && !isAnyViewerOpen,
                                    volume = if (isMuted) 0f else 1f,
                                    reportProgress = true,
                                    onProgressUpdate = { pos ->
                                        val seconds = (pos / 1000).toInt()
                                        if (seconds != currentPositionSecondsState.intValue) {
                                            currentPositionSecondsState.intValue = seconds
                                        }
                                    },
                                    fileId = content.fileId,
                                    thumbnailData = content.minithumbnail
                                )

                                VideoMuteToggle(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp),
                                    isMuted = isMuted,
                                    onToggle = { isMuted = !isMuted }
                                )
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
                                                .crossfade(false)
                                                .build()
                                        ),
                                        contentDescription = content.caption,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
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
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
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
                                        contentDescription = stringResource(R.string.action_play),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                    } else {
                        VideoLoadingLayer(
                            content = content,
                            onCancelDownload = {
                                isAutoDownloadSuppressed = true
                                AutoDownloadSuppression.suppress(content.fileId)
                                onCancelDownloadState(content.fileId)
                            },
                            onStartDownload = {
                                isAutoDownloadSuppressed = false
                                AutoDownloadSuppression.clear(content.fileId)
                                onVideoClickState(msg)
                            }
                        )
                    }

                    VideoInteractionOverlay(
                        modifier = Modifier.matchParentSize(),
                        content = content,
                        isMediaSpoilerRevealed = isMediaSpoilerRevealed,
                        videoPosition = { layoutTracker.videoPosition },
                        onRevealSpoiler = { isMediaSpoilerRevealed = true },
                        onCancelDownload = {
                            isAutoDownloadSuppressed = true
                            AutoDownloadSuppression.suppress(content.fileId)
                            onCancelDownloadState(content.fileId)
                        },
                        onOpenVideo = {
                            isAutoDownloadSuppressed = false
                            AutoDownloadSuppression.clear(content.fileId)
                            onVideoClickState(msg)
                        },
                        onLongClick = { anchor -> onLongClickState(anchor) }
                    )

                    VideoPlaybackBadge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        durationSeconds = content.duration,
                        currentPositionSeconds = currentPositionSeconds,
                        showCurrentProgress = (hasPath || content.supportsStreaming) && autoplayVideos
                    )

                    VideoUploadOverlay(
                        isUploading = content.isUploading,
                        uploadProgress = content.uploadProgress
                    )

                    VideoSpoilerOverlay(
                        isRevealed = isMediaSpoilerRevealed
                    )

                    if (content.caption.isEmpty() && showMetadata) {
                        VideoMetadataBadge(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            msg = msg,
                            isOutgoing = isOutgoing
                        )
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
                        val renderData = rememberMessageTextRenderData(
                            text = content.caption,
                            entities = content.entities,
                            allowBigEmoji = false,
                            isOutgoing = isOutgoing,
                            revealedSpoilers = revealedSpoilers,
                            fontSize = fontSize
                        )

                        if (renderData.isBigEmoji && renderData.bigEmojiItems.isNotEmpty()) {
                            BigEmojiContent(
                                items = renderData.bigEmojiItems,
                                sizeDp = fontSize * 5f,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        } else {
                            MessageText(
                                text = renderData.annotatedText,
                                rawText = content.caption,
                                inlineContent = renderData.inlineContent,
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
                                onClick = { offset -> onLongClickState(layoutTracker.videoPosition + offset) },
                                onLongClick = { offset -> onLongClickState(layoutTracker.videoPosition + offset) }
                            )
                        }
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

@Composable
private fun VideoMuteToggle(
    modifier: Modifier = Modifier,
    isMuted: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = modifier
            .size(30.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(R.string.cd_toggle_sound),
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoLoadingLayer(
    content: MessageContent.Video,
    onCancelDownload: () -> Unit,
    onStartDownload: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        MediaLoadingBackground(
            previewData = content.minithumbnail,
            contentScale = ContentScale.Crop,
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
            idleContentDescription = if (content.supportsStreaming) {
                stringResource(R.string.cd_stream)
            } else {
                stringResource(R.string.cd_download)
            },
            onCancelClick = onCancelDownload,
            onIdleClick = onStartDownload
        )
    }
}

@Composable
private fun VideoInteractionOverlay(
    modifier: Modifier = Modifier,
    content: MessageContent.Video,
    isMediaSpoilerRevealed: Boolean,
    videoPosition: () -> Offset,
    onRevealSpoiler: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenVideo: () -> Unit,
    onLongClick: (Offset) -> Unit
) {
    Box(
        modifier = modifier.pointerInput(
            content.isDownloading,
            content.fileId,
            isMediaSpoilerRevealed,
            content.supportsStreaming
        ) {
            detectTapGestures(
                onTap = {
                    if (!isMediaSpoilerRevealed) {
                        onRevealSpoiler()
                    } else if (content.isDownloading) {
                        onCancelDownload()
                    } else {
                        onOpenVideo()
                    }
                },
                onLongPress = { offset -> onLongClick(videoPosition() + offset) }
            )
        }
    )
}

@Composable
private fun VideoPlaybackBadge(
    modifier: Modifier = Modifier,
    durationSeconds: Int,
    currentPositionSeconds: Int,
    showCurrentProgress: Boolean
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (showCurrentProgress) {
                "${formatDuration(currentPositionSeconds)} / ${formatDuration(durationSeconds)}"
            } else {
                formatDuration(durationSeconds)
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoUploadOverlay(
    isUploading: Boolean,
    uploadProgress: Float
) {
    if (!isUploading) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        if (uploadProgress > 0f) {
            CircularWavyProgressIndicator(
                progress = { uploadProgress },
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

@Composable
private fun VideoSpoilerOverlay(
    isRevealed: Boolean
) {
    SpoilerWrapper(isRevealed = isRevealed) {
        Box(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun VideoMetadataBadge(
    modifier: Modifier = Modifier,
    msg: MessageModel,
    isOutgoing: Boolean
) {
    Box(modifier = modifier) {
        MessageMetadata(msg, isOutgoing, Color.White)
    }
}
