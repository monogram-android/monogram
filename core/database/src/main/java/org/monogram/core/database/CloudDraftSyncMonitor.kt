package org.monogram.core.database

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.PeerId

data class CloudDraftSyncStatus(val syncing: Boolean = false, val error: TelegramError? = null)

class CloudDraftSyncMonitor(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Failure(val error: TelegramError, val retryUntil: Long)
    private data class Snapshot(
        val busy: Map<Pair<PeerId, Int>?, Long> = emptyMap(),
        val errors: Map<Pair<PeerId, Int>?, Failure> = emptyMap(),
    )

    private val state = MutableStateFlow(Snapshot())
    private val sequence = AtomicLong()
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val retries = retryRequests.asSharedFlow()

    fun observe(chatId: PeerId, threadId: Int = 0) = state.map { snapshot ->
        val key = chatId to threadId
        val failure = snapshot.errors[null] ?: snapshot.errors[key]
        val error = failure?.let {
            if (it.error.retryAfterSeconds == null) it.error else it.error.copy(
                retryAfterSeconds = ((it.retryUntil - elapsedMillis()).coerceAtLeast(0) + 999)
                    .div(1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        CloudDraftSyncStatus(null in snapshot.busy || key in snapshot.busy, error)
    }.distinctUntilChanged()

    fun requestRetry() {
        retryRequests.tryEmit(Unit)
    }

    fun begin(context: Pair<PeerId, Int>? = null): Long {
        val token = sequence.incrementAndGet()
        state.update { it.copy(busy = it.busy + (context to token), errors = it.errors - context) }
        return token
    }

    fun finish(context: Pair<PeerId, Int>?, token: Long, error: TelegramError? = null) {
        val failure = error?.let {
            Failure(
                it,
                elapsedMillis() + (it.retryAfterSeconds?.toLong() ?: 0) * 1_000
            )
        }
        state.update {
            if (it.busy[context] != token) it else it.copy(
                busy = it.busy - context,
                errors = if (failure == null) it.errors - context else it.errors + (context to failure),
            )
        }
    }

    fun fail(error: TelegramError) {
        finish(null, begin(), error)
    }

    fun clearError(context: Pair<PeerId, Int>) {
        state.update { it.copy(errors = it.errors - context) }
    }

    fun reset() {
        state.value = Snapshot()
    }
}
