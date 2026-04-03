package org.monogram.presentation.features.chats.currentChat.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import coil3.video.videoFrameMillis
import org.monogram.presentation.core.util.LocalVideoPlayerPool
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun AvatarPlayer(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (LocalInspectionMode.current) {
        Box(modifier = modifier)
        return
    }
    val videoPlayerPool = LocalVideoPlayerPool.current

    val context = LocalContext.current
    var isVideoFrameReady by remember { mutableStateOf(false) }

    val exoPlayer = remember(path) {
        videoPlayerPool.acquire().apply {
            val uri = if (path.startsWith("http") || path.startsWith("content") || path.startsWith("file")) {
                path.toUri()
            } else {
                Uri.fromFile(File(path))
            }
            val mediaSource = videoPlayerPool.getMediaSourceFactory()
                .createMediaSource(MediaItem.fromUri(uri))
            setMediaSource(mediaSource)
            prepare()
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            volume = 0f
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                isVideoFrameReady = true
            }

            override fun onPlayerError(error: PlaybackException) {
                isVideoFrameReady = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.setVideoSurface(null)
            videoPlayerPool.release(exoPlayer)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = !isVideoFrameReady,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(path)
                    .decoderFactory(VideoFrameDecoder.Factory())
                    .videoFrameMillis(0)
                    .memoryCacheKey(path)
                    .diskCacheKey(path)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}