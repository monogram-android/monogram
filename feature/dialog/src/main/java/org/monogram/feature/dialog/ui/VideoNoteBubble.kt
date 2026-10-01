package org.monogram.feature.dialog.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil.compose.AsyncImage
import org.monogram.core.models.Message
import org.monogram.core.ui.loading.MonogramCircularProgress
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaRepository
import java.io.File
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable

internal const val VIDEO_NOTE_PLAYING_DP = 240
val LocalMessageConversationActive = staticCompositionLocalOf { true }

@Composable
internal fun VideoNoteBubble(
    message: Message,
    repository: MediaRepository,
    poster: File?,
    onLongPress: (() -> Unit)?,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember(context) { MediaPlaybackHolder.session(context) }
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    val conversationActive = LocalMessageConversationActive.current &&
            lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val mediaId = "note:" + message.id.chatId.value + ":" + message.id.id
    val item = remember(message, repository, poster) {
        MediaViewerItem(
            id = mediaId,
            kind = MediaViewerKind.VIDEO_NOTE,
            source = message.mediaCacheKey?.let(repository::cachedFile)?.let(MediaSource::Local)
                ?: MediaSource.Stream(
                    ("telegram://media/" + message.id.chatId.value + "/" + message.id.id).toUri(),
                    repository.createMessageDataSourceFactory(message)
                ),
            preview = poster,
            durationSeconds = message.mediaDuration,
            aspectRatio = 1f,
            fileSize = message.fileSize,
            senderName = message.senderName,
            fileName = context.getString(R.string.dialog_media_video_note),
            sourceChatId = message.id.chatId.value,
            sourceMessageId = message.id.id,
            protectedContent = message.noforwards,
        )
    }
    val selected = session.current?.id == mediaId
    val active = selected && session.surface == MediaSurface.CHAT && conversationActive && visible
    val playing = selected && session.playing
    LaunchedEffect(mediaId, selected, visible, conversationActive) {
        session.registerNoteVisibility(mediaId, visible && conversationActive)
    }
    DisposableEffect(mediaId) { onDispose { session.detachNote(mediaId) } }
    val toggle = {
        if (selected) {
            session.returnNoteInline(mediaId)
            session.togglePlayPause()
        } else {
            session.setQueue(listOf(item), 0, autoplay = true, startMuted = false)
            session.registerNoteVisibility(mediaId, visible && conversationActive)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(VIDEO_NOTE_DP.dp)
                .clip(CircleShape)
                .background(Color.Black)
                .then(MediaTapModifier(onTap = toggle, onLongPress = onLongPress)),
            contentAlignment = Alignment.Center,
        ) {
            if (poster != null) AsyncImage(
                model = poster,
                contentDescription = stringResource(R.string.dialog_media_video_note),
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            if (active) AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        isOpaque = false
                        clipToOutline = true
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                        session.player.setVideoTextureView(this)
                    }
                },
                update = { texture ->
                    if (session.current?.id == mediaId && session.surface == MediaSurface.CHAT) session.player.setVideoTextureView(
                        texture
                    )
                },
                onRelease = { texture -> session.player.clearVideoTextureView(texture) },
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
            val progress =
                if (selected && session.durationMs > 0) (session.positionMs.toFloat() / session.durationMs).coerceIn(
                    0f,
                    1f
                ) else 0f
            val ring = MaterialTheme.colorScheme.primary
            Canvas(Modifier
                .fillMaxSize()
                .padding(3.dp)) {
                drawArc(ring.copy(alpha = 0.25f), -90f, 360f, false, style = Stroke(3.dp.toPx()))
                drawArc(ring, -90f, 360f * progress, false, style = Stroke(3.dp.toPx()))
            }
            if (selected && session.buffering) MonogramCircularProgress(
                visible = true, modifier = Modifier.size(32.dp),
                color = Color.White, status = stringResource(R.string.dialog_media_loading)
            )
            else if (!playing || !active) IconButton(
                onClick = toggle,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    if (selected && session.failed) Icons.Filled.Refresh else if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (selected && session.failed) R.string.dialog_retry_load else if (playing) R.string.dialog_media_pause else R.string.dialog_media_play),
                    tint = Color.White
                )
            }
        }
        Text(
            if (selected) formatMediaDuration((session.positionMs / 1000L).toInt()) + " / " + formatMediaDuration(
                (session.durationMs / 1000L).toInt()
            )
            else formatMediaDuration(
                ((message.mediaDuration ?: 0).toLong() * 1000 / 1000L).toInt()
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
