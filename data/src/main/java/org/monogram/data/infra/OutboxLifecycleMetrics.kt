package org.monogram.data.infra

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

internal class OutboxLifecycleMetrics(
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    data class Snapshot(
        val active: Int = 0,
        val completed: Long = 0,
        val p50Ms: Long = 0,
        val p95Ms: Long = 0,
        val p99Ms: Long = 0
    )

    private val startedAtByMessage = ConcurrentHashMap<Key, Long>()
    private val samplesMs = ArrayDeque<Long>()
    private var completed = 0L
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun recordPending(chatId: Long, temporaryMessageId: Long, startedAtMs: Long = nowMs()) {
        startedAtByMessage.putIfAbsent(Key(chatId, temporaryMessageId), startedAtMs)
        publish()
    }

    fun recordTerminal(chatId: Long, temporaryMessageId: Long, completedAtMs: Long = nowMs()) {
        val startedAtMs = startedAtByMessage.remove(Key(chatId, temporaryMessageId)) ?: return
        synchronized(samplesMs) {
            if (samplesMs.size == MAX_SAMPLES) samplesMs.removeFirst()
            samplesMs.addLast((completedAtMs - startedAtMs).coerceAtLeast(0L))
            completed++
        }
        publish()
    }

    private fun publish() {
        val latencies = synchronized(samplesMs) { samplesMs.sorted() }
        _snapshot.value = Snapshot(
            active = startedAtByMessage.size,
            completed = completed,
            p50Ms = percentile(latencies, 0.50),
            p95Ms = percentile(latencies, 0.95),
            p99Ms = percentile(latencies, 0.99)
        )
    }

    private fun percentile(samples: List<Long>, percentile: Double): Long {
        if (samples.isEmpty()) return 0L
        return samples[(ceil(samples.size * percentile).toInt() - 1).coerceIn(0, samples.lastIndex)]
    }

    private data class Key(val chatId: Long, val temporaryMessageId: Long)

    private companion object {
        const val MAX_SAMPLES = 256
    }
}
