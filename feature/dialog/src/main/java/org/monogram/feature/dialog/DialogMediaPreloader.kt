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

    fun onVisible(messages: List<Message>, visibleIds: Set<Int>) {
        val plan = DialogMediaPreload.plan(messages, visibleIds)
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
        lastSignature = null
        mediaJob?.cancel()
        ivJob?.cancel()
        mediaJob = null
        ivJob = null
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
        }
    }
}
