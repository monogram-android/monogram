package org.monogram.core.ui.media

import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import org.monogram.core.ui.R
import org.monogram.core.ui.components.mediaTime

/** Set by the host activity while the task is in picture-in-picture. */
val LocalPictureInPictureActive = staticCompositionLocalOf { false }

fun MediaPlaybackSession.keepsMiniPlayer(): Boolean =
    audioOnly || current?.isVideo == true

fun MediaPlaybackSession.showsMiniPlayer(pictureInPicture: Boolean = false): Boolean {
    if (pictureInPicture || current == null) return false
    if (surface != MediaSurface.MINI_PLAYER && surface != MediaSurface.AUDIO_ONLY) return false
    return keepsMiniPlayer()
}

/**
 * Docked now-playing bar. Video plays in the tile while this bar owns the session's output;
 * picture-in-picture and "listen in background" keep the still preview.
 */
@Composable
fun MiniPlayerBar(
    session: MediaPlaybackSession,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** Docked bars round the top edge only; the chat-list bar passes a stadium shape. */
    shape: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
) {
    val item = session.current ?: return
    if (!session.keepsMiniPlayer()) return
    val position = session.positionMs
    val duration = session.durationMs
    val albumSize = session.albumSize
    val playsVideo = item.isVideo &&
        !session.audioOnly &&
        !LocalPictureInPictureActive.current &&
        session.surface == MediaSurface.MINI_PLAYER
    LaunchedEffect(playsVideo) {
        // Also covers restore and queue steps, which never went through the viewer.
        if (playsVideo) session.attachSurface(MediaSurface.MINI_PLAYER)
    }
    val announcement = stringResource(
        R.string.media_mini_player_now_playing,
        item.senderName.orEmpty(),
        mediaTime(position),
        mediaTime(duration),
        session.index + 1,
        albumSize,
    )
    val tickFraction = if (duration > 0L) position.toFloat() / duration else 0f
    val motion = mediaViewerMotionEnabled()
    val title = item.senderName?.takeIf { it.isNotBlank() }
        ?: item.caption?.takeIf { it.isNotBlank() }
        ?: item.fileName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.media_viewer_label)
    val meta = buildString {
        append(mediaTime(position))
        if (duration > 0L) append(" / ").append(mediaTime(duration))
        if (albumSize > 1) append("  ·  ${session.index + 1}/$albumSize")
    }

    Surface(
        onClick = onExpand,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            MediaProgressTick(
                fraction = tickFraction,
                playing = session.playing,
                barHeight = 3.dp,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics { contentDescription = announcement }
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    AnimatedContent(
                        targetState = playsVideo,
                        transitionSpec = {
                            fadeIn(MediaMotion.effects(motion)) togetherWith
                                fadeOut(MediaMotion.quick(motion))
                        },
                        label = "miniTile",
                    ) { video ->
                        if (video) {
                            AndroidView(
                                factory = { ctx ->
                                    TextureView(ctx).apply {
                                        isOpaque = true
                                        isClickable = false
                                        isFocusable = false
                                        session.player.setVideoTextureView(this)
                                    }
                                },
                                onRelease = { texture ->
                                    runCatching { session.player.clearVideoTextureView(texture) }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            item.preview?.let { preview ->
                                AsyncImage(
                                    model = preview,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize().clickable(onClick = onExpand))
                }
                Column(Modifier.weight(1f).clickable(onClick = onExpand)) {
                    // Only the title: the clock below it changes every second.
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            (slideInVertically(MediaMotion.spatial(motion)) { it / 2 } +
                                fadeIn(MediaMotion.effects(motion))) togetherWith
                                (slideOutVertically(MediaMotion.quick(motion)) { -it / 2 } +
                                    fadeOut(MediaMotion.quick(motion)))
                        },
                        label = "miniTitle",
                    ) { current ->
                        Text(
                            text = current,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                AnimatedVisibility(
                    visible = session.hasNextVideo,
                    enter = expandHorizontally(MediaMotion.spatial(motion)) +
                        fadeIn(MediaMotion.effects(motion)),
                    exit = shrinkHorizontally(MediaMotion.quick(motion)) +
                        fadeOut(MediaMotion.quick(motion)),
                ) {
                    IconButton(
                        onClick = { session.selectNextVideo() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, stringResource(R.string.media_mini_player_next))
                    }
                }
                IconButton(onClick = onExpand, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        stringResource(R.string.media_mini_player_expand),
                    )
                }
                FilledIconButton(
                    onClick = { session.togglePlayPause() },
                    colors = IconButtonDefaults.filledIconButtonColors(),
                    modifier = Modifier.size(40.dp),
                ) {
                    AnimatedContent(
                        targetState = session.playing,
                        transitionSpec = {
                            (scaleIn(MediaMotion.spatial(motion), initialScale = 0.6f) +
                                fadeIn(MediaMotion.effects(motion))) togetherWith
                                (scaleOut(MediaMotion.quick(motion), targetScale = 0.6f) +
                                    fadeOut(MediaMotion.quick(motion)))
                        },
                        label = "miniPlayPause",
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (playing) R.string.media_mini_player_pause else R.string.media_mini_player_play,
                            ),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.media_mini_player_collapse))
                }
            }
        }
    }
}

@Composable
fun MiniPlayerRestoreViewer(
    session: MediaPlaybackSession,
    onDismiss: () -> Unit,
) {
    val items = session.queue
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pip = LocalPictureInPictureController.current
    MediaViewerHost(
        album = rememberAlbumState(items, session.index),
        onDismiss = onDismiss,
        actions = MediaViewerActions(
            onListenInBackground = {
                session.listenInBackground()
                onDismiss()
            },
            canPictureInPicture = pip?.supported == true,
            onEnterPictureInPicture = { pip?.enter() },
        ),
        headerTitle = session.current?.senderName,
        session = session,
        startMutedOverride = session.muted,
    )
}

/**
 * Picture-in-picture stage. It renders inside the activity window (a dialog is not
 * composited in PiP), and it reuses the same player and surface as the viewer.
 */
@Composable
fun MediaViewerPipStage(session: MediaPlaybackSession, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MediaViewerTokens.Letterbox)) {
        AndroidView(
            factory = { ctx ->
                android.view.TextureView(ctx).apply {
                    isOpaque = true
                    session.player.setVideoTextureView(this)
                }
            },
            onRelease = { texture -> runCatching { session.player.clearVideoTextureView(texture) } },
            modifier = Modifier.fillMaxSize().padding(2.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(
                onClick = { session.togglePlayPause() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (session.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (session.playing) R.string.media_video_pause else R.string.media_video_play,
                    ),
                )
            }
            if (session.hasNextVideo) {
                FilledIconButton(
                    onClick = { session.selectNextVideo() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Default.SkipNext, stringResource(R.string.media_mini_player_next))
                }
            }
        }
        Spacer(Modifier.fillMaxSize())
    }
}
