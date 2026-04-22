package org.monogram.presentation.features.chats.currentChat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.PlaybackException

@Composable
fun InlineVideoPlayer(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    animate: Boolean = true,
    volume: Float = 0f,
    placeholderData: Any? = null,
    fileId: Int = 0,
    reportProgress: Boolean = false,
    onProgressUpdate: (Long) -> Unit = {},
    onDurationKnown: (Long) -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    onFirstFrameRendered: () -> Unit = {},
    onPlaybackError: (PlaybackException) -> Unit = {}
) {
    VideoStickerPlayer(
        path = path,
        type = VideoType.Gif,
        modifier = modifier,
        animate = animate,
        shouldLoop = true,
        volume = volume,
        contentScale = contentScale,
        onProgressUpdate = onProgressUpdate,
        onDurationKnown = onDurationKnown,
        onPlaybackEnded = onPlaybackEnded,
        onFirstFrameRendered = onFirstFrameRendered,
        onPlaybackError = onPlaybackError,
        reportProgress = reportProgress,
        fileId = fileId,
        thumbnailData = placeholderData
    )
}
