package org.monogram.feature.dialog.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import org.monogram.core.models.Message
import org.monogram.core.ui.components.LocalMediaAnimationEnabled
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.network.http.MediaRepository
import java.io.File

@Composable
internal fun ChatInlineVideo(
    message: Message,
    repository: MediaRepository,
    visible: Boolean,
    autoplay: Boolean,
    poster: File?,
    durationSeconds: Int?,
    onOpen: () -> Unit,
    onLongPress: (() -> Unit)?,
    compact: Boolean,
    durationAtStart: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationEnabled = LocalMediaAnimationEnabled.current
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    val mediaId = "${message.id.chatId.value}:${message.id.id}"
    var playingHere by remember(mediaId) { mutableStateOf(false) }
    var failedHere by remember(mediaId) { mutableStateOf(false) }
    val item = remember(mediaId, message.fileSize, message.mediaDuration, repository) {
        MediaViewerItem(
            id = mediaId,
            kind = MediaViewerKind.VIDEO,
            source = MediaSource.Stream(
                uri = Uri.parse("telegram://media/${message.id.chatId.value}/${message.id.id}"),
                factory = repository.createMessageDataSourceFactory(message),
            ),
            preview = poster,
            durationSeconds = message.mediaDuration,
            aspectRatio = mediaAspectRatio(message.mediaWidth, message.mediaHeight),
            fileSize = message.fileSize,
        )
    }

    LaunchedEffect(visible, autoplay, animationEnabled, mediaId, item) {
        snapshotFlow {
            val surface = session.surface
            val currentId = session.current?.id
            val busyElsewhere = surface == MediaSurface.VIEWER || surface == MediaSurface.PIP
            visible && autoplay && animationEnabled &&
                    !session.isMessagePlayback &&
                    (!busyElsewhere || currentId == mediaId) &&
                    surface != MediaSurface.VIEWER &&
                    surface != MediaSurface.PIP
        }.distinctUntilChanged().collect { shouldPlay ->
            if (!shouldPlay) {
                if (session.current?.id == mediaId && session.surface == MediaSurface.CHAT) {
                    session.pause()
                }
                return@collect
            }
            session.mute(true)
            session.setQueue(listOf(item), 0, autoplay = true, startMuted = true)
            session.attachSurface(MediaSurface.CHAT)
        }
    }
    LaunchedEffect(mediaId) {
        snapshotFlow {
            val owns = session.current?.id == mediaId && session.surface == MediaSurface.CHAT
            owns to (session.failed && session.current?.id == mediaId)
        }.distinctUntilChanged().collect { (owns, failed) ->
            playingHere = owns
            failedHere = failed
        }
    }
    DisposableEffect(mediaId) {
        onDispose {
            if (session.current?.id == mediaId && session.surface == MediaSurface.CHAT) {
                session.pause()
                session.attachSurface(MediaSurface.STOPPED)
            }
        }
    }

    if (playingHere) {
        val player = session.player
        Box(
            modifier = modifier.then(MediaTapModifier(onTap = onOpen, onLongPress = onLongPress)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        player.setVideoTextureView(this)
                    }
                },
                onRelease = { texture -> runCatching { player.clearVideoTextureView(texture) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        VideoThumb(
            thumb = poster,
            image = poster,
            durationSeconds = durationSeconds,
            failed = failedHere,
            loading = false,
            previewLoading = poster == null,
            fileSize = message.fileSize,
            onPlay = onOpen,
            compact = compact,
            durationAtStart = durationAtStart,
            onLongPress = onLongPress,
            modifier = modifier,
        )
    }
}
