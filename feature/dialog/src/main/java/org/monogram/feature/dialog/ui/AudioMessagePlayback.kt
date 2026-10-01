package org.monogram.feature.dialog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.models.Message
import org.monogram.core.ui.media.MediaPlaybackSession
import org.monogram.core.ui.media.MediaSource
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository
import java.io.File

internal val LocalAudioMessagePlayback = androidx.compose.runtime.staticCompositionLocalOf<AudioMessagePlayback?> { null }

@androidx.compose.runtime.Composable
fun AudioMessagePlaybackScope(enabled: Boolean = true, content: @androidx.compose.runtime.Composable (() -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = androidx.compose.runtime.remember(context) { org.monogram.core.ui.media.MediaPlaybackHolder.session(context) }
    val playback = androidx.compose.runtime.remember(session) { AudioPlaybackHolder.get(session) }
    androidx.compose.runtime.LaunchedEffect(enabled) { if (!enabled) { playback.clear(); session.stop() } }
    androidx.compose.runtime.CompositionLocalProvider(LocalAudioMessagePlayback provides playback) { content(playback::toggleCurrent) }
}

private object AudioPlaybackHolder {
    private var instance: AudioMessagePlayback? = null
    fun get(session: MediaPlaybackSession): AudioMessagePlayback =
        instance ?: AudioMessagePlayback(session).also { instance = it }
}

internal class AudioMessagePlayback(
    private val session: MediaPlaybackSession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var request: Job? = null
    private var generation = 0L
    private var repository: MediaRepository? = null
    private var cacheKey: String? = null
    private var retryMessage: Message? = null
    private var retryRepository: MediaRepository? = null
    private var retryTitle = ""
    var pendingId by mutableStateOf<String?>(null)
        private set
    var failedId by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            snapshotFlow { session.current?.id }.collectLatest { id ->
                if (pendingId != null && pendingId != id) cancelRequest()
                val retainedId = retryMessage?.let { "audio:" + it.id.chatId.value + ":" + it.id.id }
                if (retainedId != null && retainedId != id) clear()
            }
        }
    }

    fun play(message: Message, repo: MediaRepository?, file: File?, title: String) {
        val id = "audio:" + message.id.chatId.value + ":" + message.id.id
        if (pendingId == id) {
            cancel(id)
            return
        }
        if (session.current?.id == id && session.current?.source != null) {
            session.togglePlayPause()
            return
        }
        cancelRequest()
        failedId = null
        retryMessage = message
        retryRepository = repo
        retryTitle = title
        val descriptor = MediaViewerItem(
            id = id,
            kind = if (message.mediaKind == "voice") MediaViewerKind.VOICE else MediaViewerKind.AUDIO,
            source = file?.let(MediaSource::Local),
            fileName = title,
            senderName = message.senderName,
            durationSeconds = message.mediaDuration,
            protectedContent = message.noforwards,
            sourceChatId = message.id.chatId.value,
            sourceMessageId = message.id.id,
            loading = file == null,
        )
        if (file == null && repo == null) { failedId = id; return }
        session.setQueue(listOf(descriptor), 0, autoplay = file != null, startMuted = false)
        session.attachSurface(MediaSurface.AUDIO_ONLY)
        if (file != null) {
            clearRetry()
            session.play()
            return
        }
        pendingId = id
        repository = repo
        cacheKey = message.mediaCacheKey
        val token = generation
        request = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { checkNotNull(repo).ensureLocalMessageMedia(message, MediaPriority.USER) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Outcome.Err("audio download failed")
            }
            if (generation != token || session.current?.id != id) return@launch
            pendingId = null
            request = null
            when (result) {
                is Outcome.Ok -> {
                    repository = null
                    cacheKey = null
                    clearRetry()
                    session.setQueue(listOf(descriptor.copy(source = MediaSource.Local(result.value), loading = false)), 0,
                        autoplay = true, startMuted = false)
                    session.play()
                }
                is Outcome.Err -> {
                    failedId = id
                    session.setQueue(listOf(descriptor.copy(loading = false, failed = true)), 0,
                        autoplay = false, startMuted = false)
                }
            }
        }
    }

    fun cancel(id: String) {
        if (pendingId != id) return
        cancelRequest()
        if (session.current?.id == id) session.stop()
    }

    private fun cancelRequest() {
        generation++
        request?.cancel()
        request = null
        if (pendingId != null) cacheKey?.let { repository?.cancel(it) }
        pendingId = null
        repository = null
        cacheKey = null
    }

    fun toggleCurrent() {
        val id = session.current?.id ?: return
        if (pendingId == id) cancel(id)
        else if (session.current?.source == null && failedId == id) {
            retryMessage?.let { play(it, retryRepository, null, retryTitle) }
        } else session.togglePlayPause()
    }

    private fun clearRetry() {
        retryMessage = null
        retryRepository = null
        retryTitle = ""
    }

    fun clear() {
        cancelRequest()
        failedId = null
        clearRetry()
    }
}
