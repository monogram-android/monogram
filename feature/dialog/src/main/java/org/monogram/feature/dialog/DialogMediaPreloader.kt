package org.monogram.feature.dialog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.monogram.core.common.PerfLog
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.ui.DownloadSettings
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

class DialogMediaPreloader(
    private val chatId: PeerId,
    private val mediaRepository: MediaRepository?,
    private val prefetchInstantView: suspend (String, Int) -> Unit,
    private val scope: CoroutineScope,
) {
    private var mediaJob: Job? = null
    private var ivJob: Job? = null
    private var lastSignature: String? = null
    private var previousVisibleId: Int? = null

    fun onVisible(messages: List<Message>, visibleIds: Set<Int>) {
        if (visibleIds.isEmpty()) {
            previousVisibleId = null
            lastSignature = null
            mediaJob?.cancel()
            ivJob?.cancel()
            mediaJob = null
            ivJob = null
            mediaRepository?.protectCacheKeys("dialog:${chatId.value}", emptySet())
            mediaRepository?.cancelChat(chatId, belowPriority = MediaPriority.VISIBLE)
            return
        }
        val settings = DownloadSettings.state.value
        val visibleIndex = messages.indexOfFirst { it.id.id in visibleIds }.takeIf { it >= 0 }
        val previousIndex = previousVisibleId?.let { id ->
            messages.indexOfFirst { it.id.id == id }.takeIf { it >= 0 }
        }
        val direction = if (visibleIndex != null && previousIndex != null) {
            visibleIndex.compareTo(previousIndex)
        } else 0
        previousVisibleId = visibleIndex?.let { messages[it].id.id }
        val plan = DialogMediaPreload.plan(
            messages,
            visibleIds,
            autoplayVideos = settings.autoplayVideos,
            autoplayGifs = settings.autoplayGifs,
            scrollDirection = direction,
        )
        mediaRepository?.protectCacheKeys("dialog:${chatId.value}", plan.mediaKeys)
        val signature = plan.signature()
        if (signature == lastSignature) return
        lastSignature = signature
        mediaJob?.cancel()
        ivJob?.cancel()
        val repository = mediaRepository
        mediaJob = scope.launch {
            repository?.cancelChatAwait(
                chatId = chatId,
                belowPriority = MediaPriority.VISIBLE,
                keepKeys = plan.mediaKeys,
            )
            val visible = plan.media.filter { it.priority >= MediaPriority.VISIBLE }
            val nearby = plan.media.filter { it.priority < MediaPriority.VISIBLE }
            coroutineScope {
                visible.forEach { task -> launch { fetch(repository, task) } }
            }
            nearby.forEach { task -> launch { fetch(repository, task) } }
        }
        ivJob = scope.launch {
            yield()
            for (page in plan.instantViews) {
                ensureActive()
                PerfLog.event("preload_iv")
                prefetchInstantView(page.url, page.hash)
            }
        }
    }

    fun close() {
        previousVisibleId = null
        lastSignature = null
        mediaJob?.cancel()
        ivJob?.cancel()
        mediaJob = null
        ivJob = null
        mediaRepository?.protectCacheKeys("dialog:${chatId.value}", emptySet())
        mediaRepository?.cancelChat(chatId, belowPriority = MediaPriority.USER)
    }

    private suspend fun fetch(repository: MediaRepository?, task: DialogMediaPreload.MediaTask) {
        when (task.fetch) {
            DialogMediaPreload.Fetch.Thumb -> {
                if (repository?.inlineThumbJpeg(task.message) != null) return
                repository?.ensureLocalMessageThumb(task.message, task.priority)
            }

            DialogMediaPreload.Fetch.Display ->
                repository?.ensureLocalMessageDisplay(task.message, task.priority)

            DialogMediaPreload.Fetch.Full ->
                repository?.ensureLocalMessageMedia(task.message, task.priority)

            DialogMediaPreload.Fetch.Prefix ->
                repository?.ensureStreamingPrefix(task.message, task.priority)
        }
    }

}
