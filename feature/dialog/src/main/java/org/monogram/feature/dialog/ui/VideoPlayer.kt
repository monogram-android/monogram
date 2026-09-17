package org.monogram.feature.dialog.ui

import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.components.MediaPreviewViewer
import org.monogram.core.ui.media.MediaSeekBar
import org.monogram.feature.dialog.R
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.mutableIntStateOf
import org.monogram.core.ui.loading.MonogramCircularProgress

internal object CompactVideoSlots {
    const val MAX = 8
    private val playing = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val current = playing.get()
            if (current >= MAX) return false
            if (playing.compareAndSet(current, current + 1)) return true
        }
    }

    fun release() {
        playing.updateAndGet { value -> (value - 1).coerceAtLeast(0) }
    }

    internal fun resetForTests() {
        playing.set(0)
    }
}

@Composable
fun VideoPlayer(
    file: File,
    isGif: Boolean,
    durationSeconds: Int?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    active: Boolean = true,
    onClick: (() -> Unit)? = null,
    caption: String? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val animationEnabled = LocalMediaAnimationEnabled.current
    val playable = active && animationEnabled
    var compactSlot by remember { mutableStateOf(false) }
    val compactOwned = remember { AtomicBoolean(false) }
    LaunchedEffect(playable, compact) {
        if (!compact) return@LaunchedEffect
        if (playable) {
            if (!compactOwned.get()) {
                val acquired = CompactVideoSlots.tryAcquire()
                compactOwned.set(acquired)
                compactSlot = acquired
            }
        } else if (compactOwned.getAndSet(false)) {
            CompactVideoSlots.release()
            compactSlot = false
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (compactOwned.getAndSet(false)) CompactVideoSlots.release()
        }
    }
    val compactPlayable = if (compact) playable && compactSlot else playable
    if (compact && !compactPlayable) {
        VideoStill(file = file, modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        return
    }
    val uri = remember(file) { Uri.fromFile(file) }
    var muted by remember(file, isGif) { mutableStateOf(isGif) }
    var fullscreen by rememberSaveable(file.absolutePath) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(isGif) }
    var positionMs by remember(file) { mutableLongStateOf(0L) }
    var durationMs by remember(file) { mutableLongStateOf((durationSeconds ?: 0) * 1000L) }
    var bufferedMs by remember(file) { mutableLongStateOf(0L) }
    var buffering by remember(file) { mutableStateOf(true) }
    var bufferGeneration by remember(file) { mutableIntStateOf(0) }
    var videoWidth by remember(file) { mutableIntStateOf(0) }
    var videoHeight by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(buffering) { if (buffering) bufferGeneration++ }

    val player = remember(uri, isGif, compact) {
        val builder = ExoPlayer.Builder(context)
        if (compact) {
            // A picker can show dozens of local loops; each needs only a small read-ahead buffer.
            builder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(250, 1000, 100, 250)
                    .setTargetBufferBytes(512 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build(),
            )
        }
        builder.build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = if (isGif) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = if (isGif) 0f else 1f
            if (isGif) {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
            playWhenReady = isGif && active && animationEnabled
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING ||
                    playbackState == Player.STATE_IDLE
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.clearVideoSurface()
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner, isGif) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
            if (event == Lifecycle.Event.ON_START && isGif && active && animationEnabled) player.play()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) player.pause()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
    }

    LaunchedEffect(player, active, animationEnabled, isGif) {
        if (active && animationEnabled && isGif) {
            player.play()
        } else if (!active || !animationEnabled) {
            player.pause()
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            val dur = player.duration
            if (dur > 0) durationMs = dur
            positionMs = player.currentPosition.coerceAtLeast(0L)
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            delay(200)
        }
    }

    val surface: @Composable (Modifier) -> Unit = { surfaceModifier ->
        AndroidView(
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    isOpaque = !isGif
                    isClickable = false
                    isFocusable = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view -> player.setVideoTextureView(view) },
            onRelease = { view -> runCatching { player.clearVideoTextureView(view) } },
            modifier = surfaceModifier,
        )
    }
    val gifSurfaceModifier =
        if (isGif && videoWidth > 0 && videoHeight > 0) {
            Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
                .aspectRatio(videoWidth.toFloat() / videoHeight.toFloat())
        } else {
            Modifier.fillMaxSize()
        }

    Box(
        modifier = modifier.clip(RoundedCornerShape(if (compact) 4.dp else 12.dp)),
        contentAlignment = if (isGif) Alignment.Center else Alignment.TopStart,
    ) {
        if (!fullscreen) {
            surface(gifSurfaceModifier)
            if (isGif && !compact) {
                Text(
                    text = stringResource(R.string.dialog_media_gif),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            MonogramCircularProgress(
                visible = buffering && !compact,
                modifier = Modifier.align(Alignment.Center),
                generation = bufferGeneration,
                size = 36.dp,
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.24f),
            )
            if (onClick != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(onClick = onClick),
                )
            }
            if (!isGif) {
                VideoControls(
                    playing = playing,
                    muted = muted,
                    fullscreen = false,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    bufferedMs = bufferedMs,
                    overlayDurationSeconds = durationSeconds,
                    onPlayPause = {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                    onMute = { muted = !muted },
                    onFullscreen = { fullscreen = true },
                    onSeek = { target ->
                        player.seekTo(target)
                        positionMs = target
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    LaunchedEffect(fullscreen, active, animationEnabled) {
        if (fullscreen || !active || !animationEnabled) player.pause()
        else if (playing) player.play()
    }
    if (fullscreen) {
        MediaPreviewViewer(
            file = file,
            contentDescription = stringResource(R.string.dialog_media_video),
            loop = isGif,
            muted = muted || isGif,
            caption = caption,
            onDismiss = { fullscreen = false },
        )
    }
}

@Composable
private fun VideoControls(
    playing: Boolean,
    muted: Boolean,
    fullscreen: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    overlayDurationSeconds: Int?,
    onPlayPause: () -> Unit,
    onMute: () -> Unit,
    onFullscreen: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMs = maxOf(durationMs, (overlayDurationSeconds ?: 0) * 1000L)
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (playing) R.string.dialog_media_pause else R.string.dialog_media_play,
                    ),
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = formatMediaDuration((positionMs / 1000L).toInt()),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 8.dp),
            )
            MediaSeekBar(
                positionMs = positionMs,
                durationMs = totalMs,
                bufferedMs = bufferedMs,
                playing = playing,
                enabled = totalMs > 0L,
                contentDescription = stringResource(R.string.dialog_media_seek),
                onScrubStart = {},
                onScrub = {},
                onScrubEnd = onSeek,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatMediaDuration((totalMs / 1000L).toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onMute, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(
                        if (muted) R.string.dialog_media_unmute else R.string.dialog_media_mute,
                    ),
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onFullscreen, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = stringResource(
                        if (fullscreen) {
                            R.string.dialog_media_exit_fullscreen
                        } else {
                            R.string.dialog_media_fullscreen
                        },
                    ),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
