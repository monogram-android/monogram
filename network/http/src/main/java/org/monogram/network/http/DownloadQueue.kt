package org.monogram.network.http

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.common.AppDispatchers
import org.monogram.core.common.DefaultAppDispatchers
import org.monogram.core.common.Outcome
import java.io.File
import java.io.FileOutputStream
import java.util.PriorityQueue
import java.util.UUID

data class DownloadRequest(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val destination: File,
    /** Higher values run first. Default is on-screen work. */
    val priority: Int = MediaPriority.DEFAULT,
)

sealed class DownloadStatus {
    data object Queued : DownloadStatus()
    data class Running(val bytesRead: Long) : DownloadStatus()
    data class Completed(val file: File) : DownloadStatus()
    data class Failed(val message: String) : DownloadStatus()
    data object Cancelled : DownloadStatus()
}

/**
 * Bounded concurrent queue for CDN and web-file HTTP fetches.
 * Datacenter `upload.getFile` parts are not queued here.
 * https://core.telegram.org/api/files
 * Higher [DownloadRequest.priority] runs first; equal priority stays FIFO.
 */
class DownloadQueue(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 2,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
) {
    private data class Queued(
        val request: DownloadRequest,
        val sequence: Long,
    ) : Comparable<Queued> {
        override fun compareTo(other: Queued): Int {
            val priority = other.request.priority.compareTo(request.priority)
            return if (priority != 0) priority else sequence.compareTo(other.sequence)
        }
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pending = PriorityQueue<Queued>()
    private var sequence = 0L
    private val statuses = MutableStateFlow<Map<String, DownloadStatus>>(emptyMap())
    private val mutex = Mutex()
    private val workers = mutableListOf<Job>()
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val active = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val publicationLock = Any()
    @Volatile private var started = false
    @Volatile private var stopped = false

    val statusMap: StateFlow<Map<String, DownloadStatus>> = statuses.asStateFlow()

    fun start() {
        if (started || stopped) return
        started = true
        repeat(maxConcurrent) {
            workers += scope.launch(dispatchers.io) {
                while (isActive && !stopped) {
                    takeNext()?.let { request -> runDownload(request) }
                }
            }
        }
    }

    suspend fun enqueue(request: DownloadRequest): String {
        setStatus(request.id, DownloadStatus.Queued)
        mutex.withLock {
            check(!stopped) { "download queue is shut down" }
            pending += Queued(request, sequence++)
        }
        wake.trySend(Unit)
        return request.id
    }

    fun cancel(id: String) {
        val job = synchronized(publicationLock) {
            if (statuses.value[id] is DownloadStatus.Completed) return
            cancelled.add(id)
            statuses.update { it + (id to DownloadStatus.Cancelled) }
            active[id]
        }
        job?.cancel()
    }

    fun shutdown() {
        stopped = true
        workers.forEach { it.cancel() }
        workers.clear()
        wake.close()
        client.close()
    }

    private suspend fun takeNext(): DownloadRequest? {
        while (!stopped) {
            val next = mutex.withLock { pending.poll()?.request }
            if (next != null) return next
            wake.receiveCatching()
        }
        return null
    }

    private suspend fun runDownload(request: DownloadRequest) = coroutineScope {
        val job = launch(start = CoroutineStart.LAZY) { performDownload(request) }
        active[request.id] = job
        try {
            if (cancelled.contains(request.id)) job.cancel() else job.start()
            job.join()
        } finally {
            active.remove(request.id, job)
        }
    }

    private suspend fun performDownload(request: DownloadRequest) {
        if (cancelled.contains(request.id)) {
            setStatus(request.id, DownloadStatus.Cancelled)
            return
        }
        setStatus(request.id, DownloadStatus.Running(0))
        val partial = File(request.destination.parentFile, ".${request.destination.name}.${request.id}.part")
        val result = try {
            request.destination.parentFile?.mkdirs()
            client.prepareGet(request.url).execute { response ->
                if (!response.status.isSuccess()) {
                    error("HTTP ${response.status.value}")
                }
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                FileOutputStream(partial).use { output ->
                    while (true) {
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        if (cancelled.contains(request.id)) throw CancellationException("download cancelled")
                        output.write(buffer, 0, read)
                        total += read
                        setStatus(request.id, DownloadStatus.Running(total))
                    }
                }
                val context = currentCoroutineContext()
                synchronized(publicationLock) {
                    context.ensureActive()
                    if (cancelled.contains(request.id) || stopped) {
                        throw CancellationException("download cancelled")
                    }
                    if (!partial.renameTo(request.destination)) {
                        error("cannot publish downloaded file")
                    }
                    statuses.update { it + (request.id to DownloadStatus.Completed(request.destination)) }
                }
                Outcome.Ok(request.destination)
            }
        } catch (e: CancellationException) {
            partial.delete()
            setStatus(request.id, DownloadStatus.Cancelled)
            return
        } catch (e: Exception) {
            Outcome.Err(e.message ?: "download failed", e)
        }

        if (result is Outcome.Err) partial.delete()

        when (result) {
            is Outcome.Ok -> setStatus(request.id, DownloadStatus.Completed(result.value))
            is Outcome.Err -> setStatus(request.id, DownloadStatus.Failed(result.message))
        }
    }

    private suspend fun setStatus(id: String, status: DownloadStatus) {
        mutex.withLock {
            statuses.update { current ->
                if (current[id] is DownloadStatus.Cancelled) current else current + (id to status)
            }
        }
    }
}
