package org.monogram.feature.dialog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.monogram.core.models.PeerId
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.MediaRepository

class PickerMediaPreloader(
    private val mediaRepository: MediaRepository?,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var lastSignature: String? = null
    private var lastKeys: Set<String> = emptySet()

    fun onPlan(plan: PickerMediaPreload.Plan) {
        val signature = plan.signature()
        if (signature == lastSignature) return
        lastSignature = signature
        val added = plan.tasks.filter { it.cacheKey !in lastKeys }
        lastKeys = plan.keys
        val repository = mediaRepository
        if (added.isEmpty()) return
        job = scope.launch {
            val visible = added.filter { it.priority >= MediaPriority.VISIBLE }
            val nearby = added.filter { it.priority < MediaPriority.VISIBLE }
            coroutineScope {
                visible.forEach { task -> launch { fetch(repository, task) } }
            }
            nearby.forEach { task -> launch { fetch(repository, task) } }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        lastSignature = null
        lastKeys.forEach { key -> mediaRepository?.cancel(key) }
        lastKeys = emptySet()
    }

    private suspend fun fetch(repository: MediaRepository?, task: PickerMediaPreload.Task) {
        val repo = repository ?: return
        when {
            task.fetch == PickerMediaPreload.Fetch.Thumb || task.item.gif -> repo.ensureIndexedMedia(
                peerId = PeerId(task.item.documentId),
                messageId = 0,
                cacheKey = task.cacheKey,
                thumb = task.fetch == PickerMediaPreload.Fetch.Thumb,
                priority = task.priority,
                mediaKind = if (task.item.gif) "gif" else null,
            )

            else -> repo.ensureCustomEmoji(task.item.documentId, priority = task.priority)
        }
    }
}
