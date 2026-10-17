package org.monogram.core.ui.media

import android.graphics.Matrix
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.monogram.core.ui.R
import org.monogram.core.ui.components.mediaTime
import org.monogram.core.ui.menu.AppMenuGroup
import org.monogram.core.ui.menu.AppMenuItem
import org.monogram.core.ui.menu.AppMenuPopup
import org.monogram.core.ui.menu.AppMenuSurface
import kotlin.math.roundToInt

@Composable
fun MessagePlaybackBar(
    session: MediaPlaybackSession,
    onOpenMessage: () -> Unit,
    modifier: Modifier = Modifier,
    roundBottomCorners: Boolean = false,
    onToggle: () -> Unit = session::togglePlayPause,
) {
    val item = session.current ?: return
    if (!item.isMessageMedia) return
    val title = item.senderName?.takeIf { it.isNotBlank() }
        ?: item.fileName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.media_viewer_label)
    val openLabel = stringResource(R.string.media_action_show_in_chat)
    var speedMenu by remember(item.id) { mutableStateOf(false) }
    val speedLabel = playbackSpeedLabel(session.speed)
    val pending = item.loading && item.source == null
    val failed = item.failed || session.failed

    Surface(
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = if (roundBottomCorners) {
            RoundedCornerShape(16.dp)
        } else {
            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(onClickLabel = openLabel, onClick = onOpenMessage),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mediaTime(session.positionMs) + " / " + mediaTime(session.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                    val icon = when {
                        pending -> Icons.Default.Close
                        failed -> Icons.Default.Refresh
                        session.playing -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    }
                    val label = when {
                        pending -> R.string.media_mini_player_collapse
                        failed -> R.string.media_video_retry
                        session.playing -> R.string.media_video_pause
                        else -> R.string.media_video_play
                    }
                    Icon(icon, stringResource(label))
                }
                if (session.buffering || pending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            Box {
                IconButton(
                    onClick = { speedMenu = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Speed, stringResource(R.string.media_menu_speed, speedLabel))
                }
                AppMenuPopup(
                    expanded = speedMenu,
                    onDismiss = { speedMenu = false },
                    alignToAnchorEnd = true,
                ) {
                    AppMenuSurface {
                        AppMenuGroup {
                            listOf(1f, 1.5f, 2f).forEach { speed ->
                                AppMenuItem(
                                    text = playbackSpeedLabel(speed),
                                    onClick = {
                                        speedMenu = false
                                        session.changeSpeed(speed)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = session::stop, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.media_mini_player_collapse))
            }
        }
    }
}

@Composable
fun FloatingVideoNote(
    session: MediaPlaybackSession,
    onOpenMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var rightSide by rememberSaveable { mutableStateOf(true) }
    var normalizedY by rememberSaveable { mutableFloatStateOf(0.75f) }
    val item = session.current ?: return
    if (!item.isVideoNote || !session.floatingNote ||
        session.surface != MediaSurface.MINI_PLAYER || session.audioOnly ||
        LocalPictureInPictureActive.current
    ) return

    var controlsVisible by remember(item.id) { mutableStateOf(true) }
    LaunchedEffect(item.id, session.playing, session.buffering, session.failed) {
        controlsVisible = true
        if (session.playing && !session.buffering && !session.failed) {
            delay(1_000L)
            controlsVisible = false
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(8.dp),
    ) {
        val diameter = minOf(144.dp, maxWidth, maxHeight)
        // There is no room for three distinct 48dp actions in smaller windows.
        if (diameter < 144.dp) return@BoxWithConstraints
        val density = LocalDensity.current
        val maxX = with(density) { (maxWidth - diameter).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - diameter).toPx() }.coerceAtLeast(0f)
        var dragX by remember(maxX, maxY) { mutableStateOf<Float?>(null) }
        val x = dragX ?: if (rightSide) maxX else 0f
        val y = normalizedY.coerceIn(0f, 1f) * maxY
        val progress = if (session.durationMs > 0L) {
            (session.positionMs.toFloat() / session.durationMs).coerceIn(0f, 1f)
        } else 0f

        Box(
            modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(diameter)
                .testTag("floating-video-note")
                .clickable { session.togglePlayPause() }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragStart = { dragX = if (rightSide) maxX else 0f },
                        onDragEnd = {
                            rightSide = (dragX ?: x) >= maxX / 2f
                            dragX = null
                        },
                        onDragCancel = { dragX = null },
                    ) { change, delta ->
                        change.consume()
                        dragX = ((dragX ?: if (rightSide) maxX else 0f) + delta.x)
                            .coerceIn(0f, maxX)
                        if (maxY > 0f) {
                            normalizedY = (normalizedY + delta.y / maxY).coerceIn(0f, 1f)
                        }
                    }
                },
        ) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                AsyncImage(
                    model = item.preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                key(session, item.id) {
                    AndroidView(
                        factory = { context ->
                            TextureView(context).apply {
                                isOpaque = false
                                isClickable = false
                                isFocusable = false
                                session.player.setVideoTextureView(this)
                            }
                        },
                        update = { texture ->
                            val ratio = session.aspectRatio.takeIf { it > 0f } ?: 1f
                            texture.setTransform(Matrix().apply {
                                setScale(
                                    maxOf(1f, ratio),
                                    maxOf(1f, 1f / ratio),
                                    texture.width / 2f,
                                    texture.height / 2f,
                                )
                            })
                        },
                        onRelease = { texture ->
                            runCatching { session.player.clearVideoTextureView(texture) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            if (session.buffering) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(56.dp),
                    strokeWidth = 2.dp,
                )
            }
            AnimatedVisibility(
                visible = controlsVisible || !session.playing || session.buffering || session.failed,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                FilledTonalIconButton(
                    onClick = session::togglePlayPause,
                    enabled = !session.failed,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        if (session.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        stringResource(if (session.playing) R.string.media_video_pause else R.string.media_video_play),
                    )
                }
            }
        }
    }
}

private fun playbackSpeedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
