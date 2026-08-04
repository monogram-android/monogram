package org.monogram.data.datasource.cache

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal class MessageCacheWriter(
    scope: CoroutineScope,
    private val applyBatch: suspend (List<MessageCacheMutation>) -> Unit,
    private val maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
) {
    data class Stats(
        val batches: Long = 0,
        val mutations: Long = 0,
        val lastBatchSize: Int = 0,
        val lastLatencyMs: Long = 0,
        val failures: Long = 0,
        val retries: Long = 0,
        val enqueued: Long = 0,
        val pending: Int = 0,
        val dropped: Long = 0
    )

    private val mutations = Channel<MessageCacheMutation>(Channel.UNLIMITED)
    private val pending = AtomicInteger()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    init {
        scope.launch {
            for (first in mutations) {
                pending.decrementAndGet()
                val batch = ArrayList<MessageCacheMutation>(maxBatchSize)
                batch += first
                while (batch.size < maxBatchSize) {
                    mutations.tryReceive().getOrNull()?.let { mutation ->
                        pending.decrementAndGet()
                        batch += mutation
                    } ?: break
                }

                val startedAt = System.nanoTime()
                var retries = 0L
                var failure: Throwable? = null
                var attempt = 0
                do {
                    failure = runCatching { applyBatch(batch) }.exceptionOrNull()
                    attempt++
                    if (failure != null && attempt < MAX_ATTEMPTS) {
                        retries++
                        delay(RETRY_DELAY_MS)
                    }
                } while (failure != null && attempt < MAX_ATTEMPTS)
                if (failure != null) Log.e(
                    TAG,
                    "Message cache batch failed: size=${batch.size}",
                    failure
                )
                _stats.update { previous ->
                    previous.copy(
                        batches = previous.batches + 1,
                        mutations = previous.mutations + batch.size,
                        lastBatchSize = batch.size,
                        lastLatencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                        failures = previous.failures + if (failure == null) 0 else 1,
                        retries = previous.retries + retries,
                        pending = pending.get()
                    )
                }
            }
        }
    }

    fun enqueue(mutation: MessageCacheMutation): Boolean {
        pending.incrementAndGet()
        if (mutations.trySend(mutation).isSuccess) {
            _stats.update { stats ->
                stats.copy(enqueued = stats.enqueued + 1, pending = pending.get())
            }
            return true
        }

        pending.decrementAndGet()
        _stats.update { stats ->
            stats.copy(dropped = stats.dropped + 1, pending = pending.get())
        }
        return false
    }

    private companion object {
        const val TAG = "MessageCacheWriter"
        const val DEFAULT_MAX_BATCH_SIZE = 64
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 50L
    }
}
