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
        job?.cancel()
        val dropped = lastKeys - plan.keys
        lastKeys = plan.keys
        val repository = mediaRepository
        job = scope.launch {
            dropped.forEach { key -> repository?.cancel(key) }
            val visible = plan.tasks.filter { it.priority >= MediaPriority.VISIBLE }
            val nearby = plan.tasks.filter { it.priority < MediaPriority.VISIBLE }
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
        when (task.fetch) {
            PickerMediaPreload.Fetch.Thumb -> repo.ensureIndexedMedia(
                peerId = PeerId(task.item.documentId),
                messageId = 0,
                cacheKey = task.cacheKey,
                thumb = true,
                priority = task.priority,
            )
            PickerMediaPreload.Fetch.Document ->
                repo.ensureCustomEmoji(task.item.documentId, priority = task.priority)
        }
    }
}
