package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.Stream
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import coil3.compose.rememberAsyncImagePainter
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.features.chats.currentChat.components.channels.formatDuration
import org.monogram.presentation.features.chats.currentChat.components.channels.formatViews
import org.monogram.presentation.features.chats.currentChat.components.chats.ChatTimestampInfo
import org.monogram.presentation.features.chats.currentChat.components.chats.SpoilerWrapper
import org.monogram.presentation.features.chats.currentChat.components.chats.formatDuration

@Composable
fun CompactMediaMosaic(
    messages: List<MessageModel>,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
    onPhotoClick: (MessageModel) -> Unit,
    onVideoClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit = {},
    onLongClick: (Offset) -> Unit,
    showTimestampOverlay: Boolean,
    timestampStr: String,
    isRead: Boolean,
    isOutgoing: Boolean,
    isChannel: Boolean = false,
    views: Int? = null,
    sendingState: MessageSendingState? = null,
    autoDownloadMobile: Boolean = false,
    downloadUtils: IDownloadUtils,
    videoPlayerPool: VideoPlayerPool,
    autoDownloadWifi: Boolean = false,
    autoDownloadRoaming: Boolean = false,
    toProfile: (Long) -> Unit = {},
    isAnyViewerOpen: Boolean = false
) {
    val count = messages.size
    val spacing = 2.dp

    @Composable
    fun Item(
        index: Int,
        modifier: Modifier,
        contentScale: ContentScale = ContentScale.Crop
    ) {
        val msg = messages[index]
        val isLastItem = index == messages.lastIndex

        Box(modifier = modifier.clipToBounds()) {
            when (val content = msg.content) {
                is MessageContent.Photo -> {
                    PhotoItem(
                        msg = msg,
                        photo = content,
                        onPhotoClick = onPhotoClick,
                        onCancelDownload = onCancelDownload,
                        onLongClick = onLongClick,
                        contentScale = contentScale,
                        autoDownloadMobile = autoDownloadMobile,
                        autoDownloadWifi = autoDownloadWifi,
                        autoDownloadRoaming = autoDownloadRoaming,
                        modifier = Modifier.fillMaxSize(),
                        downloadUtils = downloadUtils
                    )
                }

                is MessageContent.Video -> {
                    VideoItem(
                        msg = msg,
                        video = content,
                        autoplayVideos = autoplayVideos,
                        onVideoClick = onVideoClick,
                        onCancelDownload = onCancelDownload,
                        onLongClick = onLongClick,
                        contentScale = contentScale,
                        autoDownloadMobile = autoDownloadMobile,
                        autoDownloadWifi = autoDownloadWifi,
                        autoDownloadRoaming = autoDownloadRoaming,
                        modifier = Modifier.fillMaxSize(),
                        downloadUtils = downloadUtils,
                        videoPlayerPool = videoPlayerPool,
                        isAnyViewerOpen = isAnyViewerOpen
                    )
                }

                is MessageContent.VideoNote -> {
                    VideoNoteItem(
                        msg = msg,
                        videoNote = content,
                        autoplayVideos = autoplayVideos,
                        onVideoClick = onVideoClick,
                        onCancelDownload = onCancelDownload,
                        onLongClick = onLongClick,
                        contentScale = contentScale,
                        autoDownloadMobile = autoDownloadMobile,
                        autoDownloadWifi = autoDownloadWifi,
                        autoDownloadRoaming = autoDownloadRoaming,
                        modifier = Modifier.fillMaxSize(),
                        downloadUtils = downloadUtils,
                        videoPlayerPool = videoPlayerPool,
                        isAnyViewerOpen = isAnyViewerOpen
                    )
                }

                is MessageContent.Gif -> {
                    GifItem(
                        msg = msg,
                        gif = content,
                        autoplayGifs = autoplayGifs,
                        onGifClick = onVideoClick,
                        onCancelDownload = onCancelDownload,
                        onLongClick = onLongClick,
                        contentScale = contentScale,
                        autoDownloadMobile = autoDownloadMobile,
                        autoDownloadWifi = autoDownloadWifi,
                        autoDownloadRoaming = autoDownloadRoaming,
                        modifier = Modifier.fillMaxSize(),
                        downloadUtils = downloadUtils,
                        videoPlayerPool = videoPlayerPool,
                        isAnyViewerOpen = isAnyViewerOpen
                    )
                }

                else -> {}
            }

            if (isLastItem && showTimestampOverlay) {
                TimestampPill(
                    time = timestampStr,
                    isRead = isRead,
                    isOutgoing = isOutgoing,
                    isChannel = isChannel,
                    views = views,
                    sendingState = sendingState,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        when (count) {
            1 -> {
                Box(Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.0f.coerceIn(0.8f, 1.5f))) {
                    Item(0, Modifier.fillMaxSize(), ContentScale.Fit)
                }
            }

            2 -> {
                Row(Modifier
                    .fillMaxWidth()
                    .height(200.dp)) {
                    Item(0, Modifier
                        .weight(1f)
                        .fillMaxHeight())
                    Spacer(Modifier.width(spacing))
                    Item(1, Modifier
                        .weight(1f)
                        .fillMaxHeight())
                }
            }

            3 -> {
                Column(Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)) {
                    Item(0, Modifier
                        .weight(1.5f)
                        .fillMaxWidth())
                    Spacer(Modifier.height(spacing))
                    Row(Modifier
                        .weight(1f)
                        .fillMaxWidth()) {
                        Item(1, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                        Spacer(Modifier.width(spacing))
                        Item(2, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                    }
                }
            }

            4 -> {
                Column(Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)) {
                    Row(Modifier.weight(1f)) {
                        Item(0, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                        Spacer(Modifier.width(spacing))
                        Item(1, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                    }
                    Spacer(Modifier.height(spacing))
                    Row(Modifier.weight(1f)) {
                        Item(2, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                        Spacer(Modifier.width(spacing))
                        Item(3, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                    }
                }
            }

            else -> {
                Column(Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.9f)) {
                    Row(Modifier.weight(1f)) {
                        Item(0, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                        Spacer(Modifier.width(spacing))
                        Item(1, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                    }
                    Spacer(Modifier.height(spacing))
                    Row(Modifier.weight(1f)) {
                        Item(2, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                        Spacer(Modifier.width(spacing))
                        Item(3, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                        Spacer(Modifier.width(spacing))
                        Item(4, Modifier
                            .weight(1f)
                            .fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoItem(
    msg: MessageModel,
    photo: MessageContent.Photo,
    onPhotoClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    downloadUtils: IDownloadUtils
) {
    val hasPath = !photo.path.isNullOrBlank()

    LaunchedEffect(photo.path, photo.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (!hasPath && !photo.isDownloading) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onPhotoClick(msg)
        }
    }

    var itemPosition by remember { mutableStateOf(Offset.Zero) }
    var isRevealed by remember { mutableStateOf(!photo.hasSpoiler) }

    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = photo.path,
            animationSpec = tween(300),
            label = "PhotoLoading"
        ) { path ->
            if (!path.isNullOrBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(path),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { itemPosition = it.positionInWindow() }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) isRevealed = true
                                    else onPhotoClick(msg)
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentScale = contentScale
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) {
                                        isRevealed = true
                                    } else if (photo.isDownloading) {
                                        onCancelDownload(photo.fileId)
                                    } else {
                                        onPhotoClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (photo.minithumbnail != null) {
                        Image(
                            painter = rememberAsyncImagePainter(photo.minithumbnail),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(10.dp),
                            contentScale = contentScale
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (photo.isDownloading) {
                            CircularProgressIndicator(
                                progress = { photo.downloadProgress },
                                strokeWidth = 3.dp,
                                color = Color.White
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

        SpoilerWrapper(isRevealed = isRevealed) {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun VideoItem(
    msg: MessageModel,
    video: MessageContent.Video,
    autoplayVideos: Boolean,
    onVideoClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    downloadUtils: IDownloadUtils,
    videoPlayerPool: VideoPlayerPool,
    isAnyViewerOpen: Boolean = false
) {
    val hasPath = !video.path.isNullOrBlank()

    LaunchedEffect(video.path, video.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (!hasPath && !video.isDownloading && !video.supportsStreaming) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onVideoClick(msg)
        }
    }

    var itemPosition by remember { mutableStateOf(Offset.Zero) }
    var isRevealed by remember { mutableStateOf(!video.hasSpoiler) }

    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = hasPath || video.supportsStreaming,
            animationSpec = tween(300),
            label = "VideoLoading"
        ) { targetHasPathOrStreaming ->
            if (targetHasPathOrStreaming) {
                if (autoplayVideos) {
                    val videoPath = video.path ?: "http://streaming/${video.fileId}"
                    VideoStickerPlayer(
                        path = videoPath,
                        type = VideoType.Gif,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { itemPosition = it.positionInWindow() }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        if (!isRevealed) isRevealed = true
                                        else onVideoClick(msg)
                                    },
                                    onLongPress = { offset -> onLongClick(itemPosition + offset) }
                                )
                            },
                        animate = !isAnyViewerOpen,
                        contentScale = contentScale,
                        videoPlayerPool = videoPlayerPool,
                        fileId = if (video.path == null) video.fileId else 0,
                        thumbnailData = video.minithumbnail
                    )
                } else {
                    if (hasPath) {
                        Image(
                            painter = rememberAsyncImagePainter(video.path),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { itemPosition = it.positionInWindow() }
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            if (!isRevealed) isRevealed = true
                                            else onVideoClick(msg)
                                        },
                                        onLongPress = { offset -> onLongClick(itemPosition + offset) }
                                    )
                                },
                            contentScale = contentScale
                        )
                    } else {
                        if (video.minithumbnail != null) {
                            Image(
                                painter = rememberAsyncImagePainter(video.minithumbnail),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(10.dp),
                                contentScale = contentScale
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

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    val context = LocalContext.current
                    Text(
                        text = formatDuration(context, video.duration),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) {
                                        isRevealed = true
                                    } else if (video.isDownloading) {
                                        onCancelDownload(video.fileId)
                                    } else {
                                        onVideoClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (video.minithumbnail != null) {
                        Image(
                            painter = rememberAsyncImagePainter(video.minithumbnail),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(10.dp),
                            contentScale = contentScale
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (video.isDownloading) {
                            CircularProgressIndicator(
                                progress = { video.downloadProgress },
                                strokeWidth = 3.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = if (video.supportsStreaming) Icons.Rounded.Stream else Icons.Default.Download,
                                contentDescription = if (video.supportsStreaming) "Stream" else "Download",
                                modifier = Modifier.size(28.dp),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        SpoilerWrapper(isRevealed = isRevealed) {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun VideoNoteItem(
    msg: MessageModel,
    videoPlayerPool: VideoPlayerPool,
    videoNote: MessageContent.VideoNote,
    autoplayVideos: Boolean,
    onVideoClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    downloadUtils: IDownloadUtils,
    isAnyViewerOpen: Boolean = false
) {
    val hasPath = !videoNote.path.isNullOrBlank()

    LaunchedEffect(videoNote.path, videoNote.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (!hasPath && !videoNote.isDownloading) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onVideoClick(msg)
        }
    }

    var itemPosition by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = videoNote.path,
            animationSpec = tween(300),
            label = "VideoNoteLoading"
        ) { path ->
            if (!path.isNullOrBlank()) {
                if (autoplayVideos) {
                    VideoStickerPlayer(
                        path = path,
                        type = VideoType.Gif,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { itemPosition = it.positionInWindow() }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onVideoClick(msg) },
                                    onLongPress = { offset -> onLongClick(itemPosition + offset) }
                                )
                            },
                        animate = !isAnyViewerOpen,
                        contentScale = contentScale,
                        videoPlayerPool = videoPlayerPool,
                        thumbnailData = videoNote.thumbnail
                    )
                } else {
                    val model = videoNote.thumbnail ?: path
                    Image(
                        painter = rememberAsyncImagePainter(model),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { itemPosition = it.positionInWindow() }
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onVideoClick(msg) },
                                    onLongPress = { offset -> onLongClick(itemPosition + offset) }
                                )
                            },
                        contentScale = contentScale
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
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    val context = LocalContext.current
                    Text(
                        text = formatDuration(context, videoNote.duration),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (videoNote.isDownloading) {
                                        onCancelDownload(videoNote.fileId)
                                    } else {
                                        onVideoClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    videoNote.thumbnail?.let {
                        Image(
                            painter = rememberAsyncImagePainter(it),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(10.dp),
                            contentScale = contentScale
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (videoNote.isDownloading) {
                            CircularProgressIndicator(
                                progress = { videoNote.downloadProgress },
                                strokeWidth = 3.dp,
                                color = Color.White
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
    }
}

@Composable
fun GifItem(
    msg: MessageModel,
    gif: MessageContent.Gif,
    autoplayGifs: Boolean,
    onGifClick: (MessageModel) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    downloadUtils: IDownloadUtils,
    videoPlayerPool: VideoPlayerPool,
    isAnyViewerOpen: Boolean = false
) {
    val hasPath = !gif.path.isNullOrBlank()

    LaunchedEffect(gif.path, gif.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (!hasPath && !gif.isDownloading) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onGifClick(msg)
        }
    }

    var itemPosition by remember { mutableStateOf(Offset.Zero) }
    var isRevealed by remember { mutableStateOf(!gif.hasSpoiler) }

    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = gif.path,
            animationSpec = tween(300),
            label = "GifLoading"
        ) { path ->
            if (!path.isNullOrBlank()) {
                VideoStickerPlayer(
                    path = path,
                    type = VideoType.Gif,
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { itemPosition = it.positionInWindow() }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) isRevealed = true
                                    else onGifClick(msg)
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    animate = autoplayGifs && !isAnyViewerOpen,
                    contentScale = contentScale,
                    videoPlayerPool = videoPlayerPool,
                    thumbnailData = gif.minithumbnail
                )

                if (!autoplayGifs) {
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
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "GIF",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) {
                                        isRevealed = true
                                    } else if (gif.isDownloading) {
                                        onCancelDownload(gif.fileId)
                                    } else {
                                        onGifClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (gif.minithumbnail != null) {
                        Image(
                            painter = rememberAsyncImagePainter(gif.minithumbnail),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(10.dp),
                            contentScale = contentScale
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (gif.isDownloading) {
                            CircularProgressIndicator(
                                progress = { gif.downloadProgress },
                                strokeWidth = 3.dp,
                                color = Color.White
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

        SpoilerWrapper(isRevealed = isRevealed) {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun TimestampPill(
    time: String,
    isRead: Boolean,
    isOutgoing: Boolean,
    isChannel: Boolean = false,
    views: Int? = null,
    sendingState: MessageSendingState? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isChannel) {
                views?.let { viewsCount ->
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
            }
            AnimatedContent(
                targetState = sendingState to isRead,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                },
                label = "SendingState"
            ) { (state, read) ->
                ChatTimestampInfo(
                    time = time,
                    isRead = read,
                    isOutgoing = isOutgoing,
                    sendingState = state,
                    color = Color.White
                )
            }
        }
    }
}
