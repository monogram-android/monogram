package org.monogram.presentation.features.chats.conversation.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Immutable
data class VoicePlaybackUiState(
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val currentPosition: Long = 0L,
    val duration: Long = 0L
)

interface VoicePlaybackController {
    fun stateFor(messageId: Long, fallbackDurationSeconds: Int): VoicePlaybackUiState
    fun togglePlayPause(messageId: Long, path: String?)
    fun seekTo(messageId: Long, positionFraction: Float)
}

private class EmptyVoicePlaybackController : VoicePlaybackController {
    override fun stateFor(messageId: Long, fallbackDurationSeconds: Int): VoicePlaybackUiState {
        return VoicePlaybackUiState(duration = fallbackDurationSeconds * 1000L)
    }

    override fun togglePlayPause(messageId: Long, path: String?) = Unit

    override fun seekTo(messageId: Long, positionFraction: Float) = Unit
}

val LocalVoicePlaybackController = staticCompositionLocalOf<VoicePlaybackController> {
    EmptyVoicePlaybackController()
}

@Composable
fun rememberVoicePlaybackController(): VoicePlaybackController {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    val controller = remember(player) { ExoPlayerVoicePlaybackController(player) }

    LaunchedEffect(controller.activeMessageId, controller.isPlaying) {
        while (isActive && controller.isPlaying) {
            controller.updateProgress()
            delay(50)
        }
    }

    androidx.compose.runtime.DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    return controller
}

private class ExoPlayerVoicePlaybackController(
    private val player: ExoPlayer
) : VoicePlaybackController {
    var activeMessageId by mutableStateOf<Long?>(null)
        private set
    private var activePath by mutableStateOf<String?>(null)

    var isPlaying by mutableStateOf(false)
        private set
    private var progress by mutableFloatStateOf(0f)
    private var currentPosition by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    updateProgress()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        duration = player.duration.coerceAtLeast(0L)
                        updateProgress()
                    }

                    Player.STATE_ENDED -> {
                        isPlaying = false
                        progress = 0f
                        currentPosition = 0L
                        player.seekTo(0L)
                        player.pause()
                    }
                }
            }
        })
    }

    override fun stateFor(messageId: Long, fallbackDurationSeconds: Int): VoicePlaybackUiState {
        val isActiveMessage = activeMessageId == messageId
        return if (isActiveMessage) {
            VoicePlaybackUiState(
                isPlaying = isPlaying,
                progress = progress,
                currentPosition = currentPosition,
                duration = duration.takeIf { it > 0L } ?: fallbackDurationSeconds * 1000L
            )
        } else {
            VoicePlaybackUiState(duration = fallbackDurationSeconds * 1000L)
        }
    }

    override fun togglePlayPause(messageId: Long, path: String?) {
        if (path == null) return

        if (activeMessageId != messageId || activePath != path) {
            activeMessageId = messageId
            activePath = path
            progress = 0f
            currentPosition = 0L
            duration = 0L
            player.setMediaItem(MediaItem.fromUri(Uri.parse(path)))
            player.prepare()
            player.play()
            return
        }

        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    override fun seekTo(messageId: Long, positionFraction: Float) {
        if (activeMessageId != messageId) return
        val totalDuration = player.duration
        if (totalDuration <= 0L) return
        player.seekTo((positionFraction.coerceIn(0f, 1f) * totalDuration).toLong())
        updateProgress()
    }

    fun updateProgress() {
        if (activeMessageId == null) return
        currentPosition = player.currentPosition.coerceAtLeast(0L)
        val total = player.duration.coerceAtLeast(0L)
        duration = total
        progress = if (total > 0L) currentPosition.toFloat() / total.toFloat() else 0f
    }
}
