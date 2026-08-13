package org.monogram.data.datasource.cache

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
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
    private val onTerminalFailure: suspend (List<MessageCacheMutation>, Throwable) -> Unit = { _, _ -> },
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

    private data class QueuedMutation(
        val mutation: MessageCacheMutation,
        val completion: CompletableDeferred<Unit>?
    )

    private val mutations = Channel<QueuedMutation>(Channel.UNLIMITED)
    private val pending = AtomicInteger()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    init {
        scope.launch {
            for (first in mutations) {
                pending.decrementAndGet()
                val batch = ArrayList<QueuedMutation>(maxBatchSize)
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
                    failure =
                        runCatching { applyBatch(batch.map(QueuedMutation::mutation)) }.exceptionOrNull()
                    attempt++
                    if (failure != null && attempt < MAX_ATTEMPTS) {
                        retries++
                        delay(RETRY_DELAYS_MS[attempt - 1])
                    }
                } while (failure != null && attempt < MAX_ATTEMPTS)
                if (failure != null) {
                    Log.e(
                        TAG,
                        "Message cache batch failed: size=${batch.size}",
                        failure
                    )
                    runCatching {
                        onTerminalFailure(batch.map(QueuedMutation::mutation), failure)
                    }.onFailure { invalidationFailure ->
                        Log.e(
                            TAG,
                            "Message cache coverage invalidation failed",
                            invalidationFailure
                        )
                    }
                }
                batch.forEach { queued ->
                    if (failure == null) {
                        queued.completion?.complete(Unit)
                    } else {
                        queued.completion?.completeExceptionally(failure)
                    }
                }
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
        return enqueue(mutation, completion = null)
    }

    suspend fun enqueueAndAwait(mutation: MessageCacheMutation) {
        val completion = CompletableDeferred<Unit>()
        check(enqueue(mutation, completion)) { "Message cache writer is closed" }
        completion.await()
    }

    private fun enqueue(
        mutation: MessageCacheMutation,
        completion: CompletableDeferred<Unit>?
    ): Boolean {
        pending.incrementAndGet()
        if (mutations.trySend(QueuedMutation(mutation, completion)).isSuccess) {
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
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(50L, 150L)
    }
}

internal suspend fun invalidateFailedMessageCacheCoverage(
    localDataSource: ChatLocalDataSource,
    mutations: List<MessageCacheMutation>
) {
    val chatWideInvalidations = linkedSetOf<Long>()
    val scopeInvalidations = linkedSetOf<Triple<Long, String, Long>>()

    mutations.forEach { mutation ->
        when (mutation) {
            is MessageCacheMutation.Persist -> mutation.message.run {
                scopeInvalidations += mutation.key.toInvalidationScope()
            }

            is MessageCacheMutation.PersistHistoryBatch -> {
                mutation.window.let { window ->
                    scopeInvalidations += Triple(window.chatId, window.scopeType, window.scopeId)
                }
            }

            is MessageCacheMutation.UpdateWindow -> mutation.window.let { window ->
                scopeInvalidations += Triple(window.chatId, window.scopeType, window.scopeId)
            }

            is MessageCacheMutation.ReplaceId -> mutation.message.run {
                scopeInvalidations += mutation.key.toInvalidationScope()
            }

            is MessageCacheMutation.UpdateContent -> chatWideInvalidations += mutation.chatId
            is MessageCacheMutation.UpdateInteraction -> chatWideInvalidations += mutation.chatId
            is MessageCacheMutation.MarkRead -> chatWideInvalidations += mutation.chatId
            is MessageCacheMutation.DeleteMessages -> chatWideInvalidations += mutation.chatId
            is MessageCacheMutation.UpdateMediaPath -> chatWideInvalidations += mutation.chatId
        }
    }

    chatWideInvalidations.forEach { chatId ->
        localDataSource.invalidateMessageWindowCoverageForChat(chatId)
    }
    scopeInvalidations
        .filterNot { (chatId, _, _) -> chatId in chatWideInvalidations }
        .forEach { (chatId, scopeType, scopeId) ->
            localDataSource.invalidateMessageWindowCoverage(chatId, scopeType, scopeId)
        }
}

private fun org.monogram.domain.repository.ConversationKey.toInvalidationScope() =
    Triple(chatId, scopeType, scopeId)
