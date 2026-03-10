package org.monogram.presentation.features.chats.currentChat.components

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun rememberVoicePlayer(path: String?): VoicePlayerState {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    val state = remember(path) { VoicePlayerState(player, path) }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    LaunchedEffect(path) {
        if (path != null) {
            player.setMediaItem(MediaItem.fromUri(Uri.parse(path)))
            player.prepare()
        }
    }

    return state
}

class VoicePlayerState(
    private val player: ExoPlayer,
    private val path: String?
) {
    var isPlaying by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var currentPosition by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration
                } else if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                    progress = 0f
                    currentPosition = 0
                    player.seekTo(0)
                    player.pause()
                }
            }
        })
    }

    @Composable
    fun ProgressUpdater() {
        LaunchedEffect(isPlaying) {
            while (isActive && isPlaying) {
                currentPosition = player.currentPosition
                val total = player.duration
                if (total > 0) {
                    progress = currentPosition.toFloat() / total.toFloat()
                }
                delay(50)
            }
        }
    }

    fun togglePlayPause() {
        if (path == null) return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(pos: Float) {
        val total = player.duration
        if (total > 0) {
            player.seekTo((pos * total).toLong())
        }
    }
}
