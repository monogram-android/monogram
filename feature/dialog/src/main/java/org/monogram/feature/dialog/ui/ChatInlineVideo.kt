package org.monogram.feature.dialog.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
    val busyElsewhere = session.surface == MediaSurface.VIEWER ||
        session.surface == MediaSurface.PIP
    val shouldPlay = visible && autoplay && animationEnabled &&
        (!busyElsewhere || session.current?.id == mediaId) &&
        session.surface != MediaSurface.VIEWER &&
        session.surface != MediaSurface.PIP
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

    LaunchedEffect(shouldPlay, mediaId, item) {
        if (!shouldPlay) {
            if (session.current?.id == mediaId && session.surface == MediaSurface.CHAT) {
                session.pause()
                session.attachSurface(MediaSurface.STOPPED)
            }
            return@LaunchedEffect
        }
        session.mute(true)
        session.setQueue(listOf(item), 0, autoplay = true, startMuted = true)
        session.attachSurface(MediaSurface.CHAT)
    }
    DisposableEffect(mediaId) {
        onDispose {
            if (session.current?.id == mediaId && session.surface == MediaSurface.CHAT) {
                session.pause()
                session.attachSurface(MediaSurface.STOPPED)
            }
        }
    }

    val playingHere = shouldPlay &&
        session.current?.id == mediaId &&
        session.surface == MediaSurface.CHAT
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
            failed = session.failed && session.current?.id == mediaId,
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
