package org.monogram.core.ui.media

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.monogram.core.ui.R
import org.monogram.core.ui.components.mediaTime

@Composable
internal fun VideoControlCluster(
    session: MediaPlaybackSession,
    chatKey: String,
    item: MediaViewerItem,
    actions: MediaViewerActions,
    onOpenCaption: () -> Unit,
    onScrubStart: () -> Unit,
    onOverflowChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    val mutedLabel = stringResource(R.string.media_video_mute)
    val unmuteLabel = stringResource(R.string.media_video_unmute)

    LaunchedEffect(chatKey) {
        val saved = MediaViewerPrefs.speed(context, chatKey)
        if (saved != session.speed) session.changeSpeed(saved)
    }

    Column(Modifier.fillMaxWidth()) {
        run {
            // Row 1 (timeline): playhead, seek with buffered range, duration.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mediaTime(if (scrubbing) scrubPosition else session.positionMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MediaSeekBar(
                    positionMs = if (scrubbing) scrubPosition else session.positionMs,
                    durationMs = session.durationMs,
                    bufferedMs = session.bufferedMs,
                    playing = session.playing,
                    enabled = session.durationMs > 0L && !session.failed,
                    contentDescription = stringResource(R.string.media_video_seek),
                    onScrubStart = { scrubbing = true; onScrubStart() },
                    onScrub = { scrubPosition = it },
                    onScrubEnd = { target ->
                        session.seekTo(target)
                        scrubPosition = target
                        scrubbing = false
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                Text(
                    text = mediaTime(session.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Row 2: mute, +/-10s, hero play/pause, overflow.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(
                    onClick = {
                        val next = !session.muted
                        session.mute(next)
                        MediaViewerPrefs.setMuted(context, chatKey, next)
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = if (session.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (session.muted) unmuteLabel else mutedLabel,
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { session.seekBy(-10_000L) },
                    enabled = session.durationMs > 0L,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.FastRewind, stringResource(R.string.media_video_back))
                }
                MediaPlayPauseHero(
                    playing = session.playing,
                    enabled = !session.failed,
                    contentDescriptionPlay = stringResource(R.string.media_video_play),
                    contentDescriptionPause = stringResource(R.string.media_video_pause),
                    onClick = { session.togglePlayPause() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                FilledTonalIconButton(
                    onClick = { session.seekBy(10_000L) },
                    enabled = session.durationMs > 0L,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.FastForward, stringResource(R.string.media_video_forward))
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(44.dp))
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}
