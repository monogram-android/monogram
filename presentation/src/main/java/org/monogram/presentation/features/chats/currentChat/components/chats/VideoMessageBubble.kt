package org.monogram.presentation.features.chats.currentChat.components.chats

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool
import org.monogram.presentation.features.chats.currentChat.components.VideoStickerPlayer
import org.monogram.presentation.features.chats.currentChat.components.VideoType

@Composable
fun VideoMessageBubble(
    content: MessageContent.Video,
    msg: MessageModel,
    isOutgoing: Boolean,
    isSameSenderAbove: Boolean,
    isSameSenderBelow: Boolean,
    fontSize: Float,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    autoplayVideos: Boolean,
    onVideoClick: (MessageModel) -> Unit,
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

    val context = LocalContext.current
    val hasPath = !content.path.isNullOrBlank()

    LaunchedEffect(content.path, content.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (!hasPath && !content.isDownloading && !content.supportsStreaming) {
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
    var currentPosition by remember { mutableLongStateOf(0L) }
    var isVisible by remember { mutableStateOf(false) }

    val screenHeightPx = remember { context.resources.displayMetrics.heightPixels }
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
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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

                val ratio = if (content.width > 0 && content.height > 0)
                    (content.width.toFloat() / content.height.toFloat()).coerceIn(0.5f, 2f)
                else 1f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 500.dp)
                        .aspectRatio(ratio)
                        .onGloballyPositioned { videoPosition = it.positionInWindow() }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isMediaSpoilerRevealed) {
                                        isMediaSpoilerRevealed = true
                                    } else if (content.isDownloading) {
                                        onCancelDownload(content.fileId)
                                    } else {
                                        onVideoClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(videoPosition + offset) }
                            )
                        }
                ) {
                    Crossfade(
                        targetState = hasPath || content.supportsStreaming,
                        animationSpec = tween(400),
                        label = "VideoLoadingState"
                    ) { targetHasPathOrStreaming ->
                        if (targetHasPathOrStreaming) {
                            if (autoplayVideos) {
                                val videoPath = content.path ?: "http://streaming/${content.fileId}"
                                VideoStickerPlayer(
                                    path = videoPath,
                                    type = VideoType.Gif,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    animate = isVisible && !isAnyViewerOpen,
                                    volume = if (isMuted) 0f else 1f,
                                    onProgressUpdate = { pos -> currentPosition = pos },
                                    videoPlayerPool = videoPlayerPool,
                                    fileId = if (content.path == null) content.fileId else 0
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
                                                .data(content.path)
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
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
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
                                        painter = rememberAsyncImagePainter(
                                            model = ImageRequest.Builder(context)
                                                .data(content.minithumbnail)
                                                .crossfade(true)
                                                .build()
                                        ),
                                        contentDescription = "Video Preview",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .blur(14.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }

                                Box(modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f)))

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (content.isDownloading) {
                                        if (content.downloadProgress > 0f) {
                                            CircularProgressIndicator(
                                                progress = { content.downloadProgress },
                                                modifier = Modifier.size(36.dp),
                                                color = Color.White,
                                                trackColor = Color.White.copy(alpha = 0.25f),
                                                strokeWidth = 2.5.dp
                                            )
                                        } else {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(36.dp),
                                                color = Color.White,
                                                strokeWidth = 2.5.dp
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            modifier = Modifier.size(18.dp),
                                            tint = Color.White
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (content.supportsStreaming) Icons.Rounded.Stream else Icons.Default.Download,
                                            contentDescription = if (content.supportsStreaming) "Stream" else "Download",
                                            modifier = Modifier.size(28.dp),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
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
                                "${formatDuration((currentPosition / 1000).toInt())} / ${formatDuration(content.duration)}"
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
                                CircularProgressIndicator(
                                    progress = { content.uploadProgress },
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.3f)
                                )
                            } else {
                                CircularProgressIndicator(
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
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)
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
                                lineHeight = (fontSize * 1.375f).sp
                            ),
                            modifier = Modifier.padding(bottom = 4.dp),
                            onSpoilerClick = { index ->
                                if (!revealedSpoilers.contains(index)) {
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