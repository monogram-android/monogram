package org.monogram.core.ui.components

import android.graphics.Outline
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@Composable
fun LoopingVideo(
    file: File,
    modifier: Modifier = Modifier,
    crop: Boolean = false,
    loop: Boolean = true,
    muted: Boolean = true,
    playWhenReady: Boolean = true,
) {
    val context = LocalContext.current
    val playbackKey = "${file.absolutePath}:${file.length()}"
    val uri = remember(playbackKey) { Uri.fromFile(file) }
    val player = remember(uri, crop) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            if (crop) {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
            prepare()
        }
    }
    val animationEnabled = LocalMediaAnimationEnabled.current
    LaunchedEffect(player, loop, muted, playWhenReady, animationEnabled) {
        player.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.volume = if (muted) 0f else 1f
        player.playWhenReady = playWhenReady && animationEnabled
    }
    DisposableEffect(player) {
        onDispose {
            player.clearVideoSurface()
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                isOpaque = false
                isClickable = false
                isFocusable = false
                if (crop) {
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            val size = minOf(view.width, view.height)
                            val left = (view.width - size) / 2
                            val top = (view.height - size) / 2
                            outline.setOval(left, top, left + size, top + size)
                        }
                    }
                    clipToOutline = true
                }
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                player.setVideoTextureView(this)
            }
        },
        onRelease = { view ->
            runCatching { player.clearVideoTextureView(view) }
        },
        modifier = modifier,
    )
}
