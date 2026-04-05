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
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.domain.models.MessageSendingState
import org.monogram.presentation.core.util.IDownloadUtils
import org.monogram.presentation.core.util.namespacedCacheKey
import org.monogram.presentation.features.chats.currentChat.AutoDownloadSuppression
import org.monogram.presentation.features.chats.currentChat.components.channels.formatDuration
import org.monogram.presentation.features.chats.currentChat.components.channels.formatViews
import org.monogram.presentation.features.chats.currentChat.components.chats.ChatTimestampInfo
import org.monogram.presentation.features.chats.currentChat.components.chats.MediaLoadingAction
import org.monogram.presentation.features.chats.currentChat.components.chats.MediaLoadingBackground
import org.monogram.presentation.features.chats.currentChat.components.chats.SpoilerWrapper

@Composable
fun CompactMediaMosaic(
    messages: List<MessageModel>,
    autoplayGifs: Boolean,
    autoplayVideos: Boolean,
    onPhotoClick: (MessageModel) -> Unit,
    onDownloadPhoto: (Int) -> Unit = {},
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
                        onDownloadPhoto = onDownloadPhoto,
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
    onDownloadPhoto: (Int) -> Unit,
    onCancelDownload: (Int) -> Unit,
    onLongClick: (Offset) -> Unit,
    autoDownloadMobile: Boolean,
    autoDownloadWifi: Boolean,
    autoDownloadRoaming: Boolean,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    downloadUtils: IDownloadUtils
) {
    val context = LocalContext.current
    var stablePath by remember(msg.id) { mutableStateOf(photo.path) }
    val hasPath = !stablePath.isNullOrBlank()
    val photoCacheKey = remember(stablePath, photo.fileId) {
        namespacedCacheKey("mosaic_photo:${photo.fileId}", stablePath)
    }
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(photo.path) {
        if (!photo.path.isNullOrBlank()) {
            stablePath = photo.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(photo.fileId)
        }
    }

    LaunchedEffect(photo.path, photo.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (photo.path.isNullOrBlank() && !photo.isDownloading && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                photo.fileId
            )
        ) {
            val shouldDownload = when {
                downloadUtils.isWifiConnected() -> autoDownloadWifi
                downloadUtils.isRoaming() -> autoDownloadRoaming
                else -> autoDownloadMobile
            }
            if (shouldDownload) onDownloadPhoto(photo.fileId)
        }
    }

    var itemPosition by remember { mutableStateOf(Offset.Zero) }
    var isRevealed by remember { mutableStateOf(!photo.hasSpoiler) }

    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = hasPath,
            animationSpec = tween(300),
            label = "PhotoLoading"
        ) { resolved ->
            if (resolved && !stablePath.isNullOrBlank()) {
                Image(
                    painter = rememberAsyncImagePainter(
                        model = ImageRequest.Builder(context)
                            .data(stablePath)
                            .apply {
                                photoCacheKey?.let {
                                    memoryCacheKey(it)
                                    diskCacheKey(it)
                                }
                            }
                            .crossfade(true)
                            .build()
                    ),
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
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) {
                                        isRevealed = true
                                    } else if (photo.isDownloading) {
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(photo.fileId)
                                        onCancelDownload(photo.fileId)
                                    } else {
                                        isAutoDownloadSuppressed = false
                                        AutoDownloadSuppression.clear(photo.fileId)
                                        onDownloadPhoto(photo.fileId)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MediaLoadingBackground(
                        previewData = photo.minithumbnail,
                        contentScale = contentScale
                    )

                    MediaLoadingAction(
                        isDownloading = photo.isDownloading,
                        progress = photo.downloadProgress,
                        idleIcon = Icons.Default.Download,
                        idleContentDescription = "Download",
                        onCancelClick = {
                            isAutoDownloadSuppressed = true
                            AutoDownloadSuppression.suppress(photo.fileId)
                            onCancelDownload(photo.fileId)
                        },
                        onIdleClick = {
                            isAutoDownloadSuppressed = false
                            AutoDownloadSuppression.clear(photo.fileId)
                            onDownloadPhoto(photo.fileId)
                        }
                    )
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
    isAnyViewerOpen: Boolean = false
) {
    val context = LocalContext.current
    var stablePath by remember(msg.id) { mutableStateOf(video.path) }
    val hasPath = !stablePath.isNullOrBlank()
    val videoCacheKey = remember(stablePath, video.fileId) {
        namespacedCacheKey("mosaic_video:${video.fileId}", stablePath)
    }
    val videoMiniCacheKey = remember(video.minithumbnail, video.fileId) {
        video.minithumbnail?.let { namespacedCacheKey("mosaic_video_mini:${video.fileId}", it) }
    }
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(video.path) {
        if (!video.path.isNullOrBlank()) {
            stablePath = video.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(video.fileId)
        }
    }

    LaunchedEffect(video.path, video.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (video.path.isNullOrBlank() && !video.isDownloading && !video.supportsStreaming && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                video.fileId
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
                    val videoPath = stablePath ?: "http://streaming/${video.fileId}"
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
                        fileId = if (stablePath == null) video.fileId else 0,
                        thumbnailData = video.minithumbnail
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
                                    .crossfade(true)
                                    .build()
                            ),
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
                                painter = rememberAsyncImagePainter(
                                    model = ImageRequest.Builder(context)
                                        .data(video.minithumbnail)
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
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) {
                                        isRevealed = true
                                    } else if (video.isDownloading) {
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(video.fileId)
                                        onCancelDownload(video.fileId)
                                    } else {
                                        isAutoDownloadSuppressed = false
                                        AutoDownloadSuppression.clear(video.fileId)
                                        onVideoClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MediaLoadingBackground(
                        previewData = video.minithumbnail,
                        contentScale = contentScale
                    )

                    MediaLoadingAction(
                        isDownloading = video.isDownloading,
                        progress = video.downloadProgress,
                        idleIcon = if (video.supportsStreaming) Icons.Rounded.Stream else Icons.Default.Download,
                        idleContentDescription = if (video.supportsStreaming) "Stream" else "Download",
                        onCancelClick = {
                            isAutoDownloadSuppressed = true
                            AutoDownloadSuppression.suppress(video.fileId)
                            onCancelDownload(video.fileId)
                        },
                        onIdleClick = {
                            isAutoDownloadSuppressed = false
                            AutoDownloadSuppression.clear(video.fileId)
                            onVideoClick(msg)
                        }
                    )
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
    val context = LocalContext.current
    var stablePath by remember(msg.id) { mutableStateOf(videoNote.path) }
    !stablePath.isNullOrBlank()
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(videoNote.path) {
        if (!videoNote.path.isNullOrBlank()) {
            stablePath = videoNote.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(videoNote.fileId)
        }
    }

    LaunchedEffect(videoNote.path, videoNote.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (videoNote.path.isNullOrBlank() && !videoNote.isDownloading && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                videoNote.fileId
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

    var itemPosition by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.clipToBounds()) {
        Crossfade(
            targetState = stablePath,
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
                        thumbnailData = videoNote.thumbnail
                    )
                } else {
                    val model = videoNote.thumbnail ?: path
                    val videoNoteCacheKey = remember(model, videoNote.fileId) {
                        namespacedCacheKey("mosaic_video_note:${videoNote.fileId}", model)
                    }
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(model)
                                .apply {
                                    videoNoteCacheKey?.let {
                                        memoryCacheKey(it)
                                        diskCacheKey(it)
                                    }
                                }
                                .crossfade(true)
                                .build()
                        ),
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
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (videoNote.isDownloading) {
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(videoNote.fileId)
                                        onCancelDownload(videoNote.fileId)
                                    } else {
                                        isAutoDownloadSuppressed = false
                                        AutoDownloadSuppression.clear(videoNote.fileId)
                                        onVideoClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MediaLoadingBackground(
                        previewData = videoNote.thumbnail,
                        contentScale = contentScale
                    )

                    MediaLoadingAction(
                        isDownloading = videoNote.isDownloading,
                        progress = videoNote.downloadProgress,
                        idleIcon = Icons.Default.Download,
                        idleContentDescription = "Download",
                        onCancelClick = {
                            isAutoDownloadSuppressed = true
                            AutoDownloadSuppression.suppress(videoNote.fileId)
                            onCancelDownload(videoNote.fileId)
                        },
                        onIdleClick = {
                            isAutoDownloadSuppressed = false
                            AutoDownloadSuppression.clear(videoNote.fileId)
                            onVideoClick(msg)
                        }
                    )
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
    isAnyViewerOpen: Boolean = false
) {
    var stablePath by remember(msg.id) { mutableStateOf(gif.path) }
    !stablePath.isNullOrBlank()
    var isAutoDownloadSuppressed by remember(msg.id) { mutableStateOf(false) }

    LaunchedEffect(gif.path) {
        if (!gif.path.isNullOrBlank()) {
            stablePath = gif.path
            isAutoDownloadSuppressed = false
            AutoDownloadSuppression.clear(gif.fileId)
        }
    }

    LaunchedEffect(gif.path, gif.isDownloading, autoDownloadMobile, autoDownloadWifi, autoDownloadRoaming) {
        if (gif.path.isNullOrBlank() && !gif.isDownloading && !isAutoDownloadSuppressed && !AutoDownloadSuppression.isSuppressed(
                gif.fileId
            )
        ) {
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
            targetState = stablePath,
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
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (!isRevealed) {
                                        isRevealed = true
                                    } else if (gif.isDownloading) {
                                        isAutoDownloadSuppressed = true
                                        AutoDownloadSuppression.suppress(gif.fileId)
                                        onCancelDownload(gif.fileId)
                                    } else {
                                        isAutoDownloadSuppressed = false
                                        AutoDownloadSuppression.clear(gif.fileId)
                                        onGifClick(msg)
                                    }
                                },
                                onLongPress = { offset -> onLongClick(itemPosition + offset) }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    MediaLoadingBackground(
                        previewData = gif.minithumbnail,
                        contentScale = contentScale
                    )

                    MediaLoadingAction(
                        isDownloading = gif.isDownloading,
                        progress = gif.downloadProgress,
                        idleIcon = Icons.Default.Download,
                        idleContentDescription = "Download",
                        onCancelClick = {
                            isAutoDownloadSuppressed = true
                            AutoDownloadSuppression.suppress(gif.fileId)
                            onCancelDownload(gif.fileId)
                        },
                        onIdleClick = {
                            isAutoDownloadSuppressed = false
                            AutoDownloadSuppression.clear(gif.fileId)
                            onGifClick(msg)
                        }
                    )
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
    modifier: Modifier = Modifier,
    isChannel: Boolean = false,
    views: Int? = null,
    sendingState: MessageSendingState? = null
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
