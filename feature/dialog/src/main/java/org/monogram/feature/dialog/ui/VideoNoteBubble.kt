package org.monogram.feature.dialog.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.monogram.core.common.Outcome
import org.monogram.network.http.MediaPriority
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import org.monogram.core.models.Message
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import java.io.File

internal const val VIDEO_NOTE_PLAYING_DP = 240

@Composable
internal fun VideoNoteBubble(
    message: Message,
    repository: MediaRepository,
    poster: File?,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    val mediaId = "${message.id.chatId.value}:${message.id.id}"
    val cacheKey = message.mediaCacheKey
    var localFile by remember(cacheKey) {
        mutableStateOf(cacheKey?.let(repository::cachedFile))
    }
    val scope = rememberCoroutineScope()
    val active = session.current?.id == mediaId && session.surface == MediaSurface.CHAT
    val playing = active && session.playing
    val sizeDp by animateDpAsState(
        targetValue = if (playing) VIDEO_NOTE_PLAYING_DP.dp else VIDEO_NOTE_DP.dp,
        animationSpec = tween(180),
        label = "videoNoteSize",
    )
    val item = remember(mediaId, localFile, message.fileSize, message.mediaDuration, repository) {
        MediaViewerItem(
            id = mediaId,
            kind = MediaViewerKind.VIDEO,
            source = localFile?.let(MediaSource::Local) ?: MediaSource.Stream(
                uri = Uri.parse("telegram://media/${message.id.chatId.value}/${message.id.id}"),
                factory = repository.createMessageDataSourceFactory(message),
            ),
            preview = poster,
            durationSeconds = message.mediaDuration,
            aspectRatio = 1f,
            fileSize = message.fileSize,
            forceLoop = true,
        )
    }
    LaunchedEffect(localFile, mediaId) {
        if (localFile != null && session.current?.id == mediaId) {
            session.setQueue(
                listOf(item),
                0,
                autoplay = session.playing,
                startMuted = false,
                preserveUserMute = true,
            )
        }
    }
    DisposableEffect(mediaId) {
        onDispose {
            if (session.current?.id == mediaId && session.surface == MediaSurface.CHAT) {
                session.pause()
            }
        }
    }
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(Color.Black)
            .then(
                MediaTapModifier(
                    onTap = {
                        if (localFile == null && cacheKey != null) {
                            scope.launch {
                                val fetched = repository.ensureLocalMessageMedia(
                                    message,
                                    MediaPriority.USER,
                                )
                                if (fetched is Outcome.Ok) localFile = fetched.value
                            }
                        }
                        if (session.current?.id == mediaId) {
                            session.mute(false)
                            session.setLoopingCurrent(true)
                            session.attachSurface(MediaSurface.CHAT)
                            session.togglePlayPause()
                        } else {
                            session.mute(false)
                            session.setQueue(listOf(item), 0, autoplay = true, startMuted = false)
                            session.setLoopingCurrent(true)
                            session.attachSurface(MediaSurface.CHAT)
                        }
                    },
                    onLongPress = onLongPress,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (poster != null) {
            AsyncImage(
                model = poster,
                contentDescription = stringResource(R.string.dialog_media_video_note),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (active) {
            val player = session.player
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        player.setVideoTextureView(this)
                    }
                },
                update = { texture ->
                    if (session.current?.id == mediaId) player.setVideoTextureView(texture)
                },
                onRelease = { texture -> runCatching { player.clearVideoTextureView(texture) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!playing) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.dialog_media_play),
                tint = Color.White,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}
