package org.monogram.feature.dialog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.monogram.core.models.PeerId
import org.monogram.network.http.MediaConsumer
import org.monogram.network.http.MediaRepository

class PickerMediaPreloader(
    private val mediaRepository: MediaRepository?,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var lastSignature: String? = null
    private val owned = LinkedHashMap<String, Owned>()

    private class Owned(
        val job: Job,
        val consumer: MediaConsumer?,
        var priority: Int,
    )

    fun onPlan(plan: PickerMediaPreload.Plan) {
        val cancelJobs = ArrayList<Job>()
        synchronized(lock) {
            val signature = plan.signature()
            if (signature == lastSignature) return
            lastSignature = signature
            val desired = LinkedHashMap<String, PickerMediaPreload.Task>()
            plan.tasks.forEach { task ->
                val previous = desired[task.cacheKey]
                if (previous == null || task.priority > previous.priority) {
                    desired[task.cacheKey] = task
                }
            }
            val removed = owned.keys.filter { it !in desired }
            removed.forEach { key ->
                owned.remove(key)?.job?.let { cancelJobs += it }
            }
            val repository = mediaRepository
            desired.forEach { (key, task) ->
                val current = owned[key]
                if (current == null) {
                    val consumer = repository?.openDownloadConsumer(key)
                    val job = scope.launch { fetch(repository, task, consumer) }
                    owned[key] = Owned(job, consumer, task.priority)
                } else if (current.priority != task.priority) {
                    current.priority = task.priority
                    val consumer = current.consumer
                    if (consumer != null && repository != null) {
                        repository.updateDownloadConsumer(consumer, task.priority)
                    }
                }
            }
        }
        cancelJobs.forEach { it.cancel() }
    }

    fun close() {
        val cancelJobs = synchronized(lock) {
            lastSignature = null
            val jobs = owned.values.map { it.job }
            owned.clear()
            jobs
        }
        cancelJobs.forEach { it.cancel() }
    }

    private suspend fun fetch(
        repository: MediaRepository?,
        task: PickerMediaPreload.Task,
        consumer: MediaConsumer?,
    ) {
        val repo = repository ?: return
        when {
            task.fetch == PickerMediaPreload.Fetch.Thumb || task.item.gif -> repo.ensureIndexedMedia(
                peerId = PeerId(task.item.documentId),
                messageId = 0,
                cacheKey = task.cacheKey,
                thumb = task.fetch == PickerMediaPreload.Fetch.Thumb,
                priority = task.priority,
                mediaKind = if (task.item.gif) "gif" else null,
                consumer = consumer,
            )

            else -> repo.ensureCustomEmoji(
                task.item.documentId,
                priority = task.priority,
                consumer = consumer,
            )
        }
    }
}
