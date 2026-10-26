package org.monogram.root

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.CloudDraft
import org.monogram.core.models.PeerId

internal class CloudDraftSync(
    private val cache: OfflineWarmup,
    private val fetch: suspend () -> Outcome<List<CloudDraft>>,
    private val save: suspend (CloudDraft) -> Outcome<Unit>,
) {
    private val refreshPending = AtomicBoolean(true)
    private val retryRejected = AtomicBoolean(false)
    private val rejected = mutableMapOf<Pair<PeerId, Int>, Long>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            coroutineScope {
                launch { cache.draftSync.retries.collect { requestRefresh() } }
                launch {
                    cache.observeCloudDraftStates().retryWhen { cause, _ ->
                        if (cause is CancellationException) throw cause
                        currentCoroutineContext().ensureActive()
                        AppLog.warn("draft-sync", "observation failed")
                        cache.draftSync.fail(TelegramError.parse("INTERNAL"))
                        delay(60_000)
                        true
                    }.collect { wake.trySend(Unit) }
                }
                requestRefresh()
                var backoff = 2_000L
                while (isActive) {
                    wake.receive()
                    delay(400)
                    val retry = try {
                        syncOnce()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        AppLog.warn("draft-sync", "synchronization failed")
                        cache.draftSync.fail(TelegramError.parse("INTERNAL"))
                        refreshPending.set(true)
                        backoff
                    }
                    if (retry > 0) {
                        delay(maxOf(retry, backoff))
                        backoff = minOf(backoff * 2, 60_000L)
                        wake.trySend(Unit)
                    } else {
                        backoff = 2_000L
                    }
                }
            }
        }
    }

    suspend fun stop() {
        val running = job
        job = null
        running?.cancelAndJoin()
        cache.draftSync.reset()
    }

    fun requestRefresh() {
        refreshPending.set(true)
        retryRejected.set(true)
        wake.trySend(Unit)
    }

    internal suspend fun syncOnce(): Long {
        if (retryRejected.getAndSet(false)) rejected.clear()
        if (refreshPending.getAndSet(false)) {
            val before =
                cache.cloudDraftStates().associateBy { it.draft.chatId to it.draft.threadId }
            when (val result = monitored(null) { fetch() }) {
                is Outcome.Err -> {
                    refreshPending.set(true)
                    return retryDelay(result)
                }

                is Outcome.Ok -> {
                    val returned = result.value.associateBy { it.chatId to it.threadId }
                    for ((key, draft) in returned) cache.applyCloudDraft(
                        draft,
                        before[key]?.revision ?: 0
                    )
                    for ((key, state) in before) {
                        if (key !in returned) cache.applyCloudDraft(
                            CloudDraft(
                                key.first,
                                key.second
                            ), state.revision
                        )
                    }
                }
            }
        }
        val states = cache.cloudDraftStates()
        rejected.keys.retainAll(states.map { it.draft.chatId to it.draft.threadId }.toSet())
        for (snapshot in states) {
            val key = snapshot.draft.chatId to snapshot.draft.threadId
            val state = cache.cloudDraftState(key.first, key.second)
            if (!state.dirty || state.draft.hasUnsupportedContent) {
                rejected.remove(key)
                cache.draftSync.clearError(key)
                continue
            }
            if (rejected[key] == state.revision) continue
            when (val result = monitored(key) {
                save(state.draft).also {
                    if (it is Outcome.Ok) cache.acknowledgeCloudDraft(
                        state.draft.chatId,
                        state.draft.threadId,
                        state.revision
                    )
                }
            }) {
                is Outcome.Ok -> rejected.remove(key)
                is Outcome.Err -> {
                    val error = result.telegramError
                    if (error.kind in setOf(
                            TelegramError.Kind.Flood,
                            TelegramError.Kind.Network,
                            TelegramError.Kind.Auth,
                            TelegramError.Kind.Session,
                            TelegramError.Kind.Internal
                        ) ||
                        error.httpCode in 500..599 || (error.retryAfterSeconds ?: 0) > 0
                    ) {
                        cache.draftSync.fail(error)
                        refreshPending.set(true)
                        return retryDelay(result)
                    }
                    rejected[key] = state.revision
                }
            }
        }
        return 0
    }

    private suspend fun <T> monitored(
        context: Pair<PeerId, Int>?,
        block: suspend () -> Outcome<T>
    ): Outcome<T> {
        val token = cache.draftSync.begin(context)
        var error: TelegramError? = null
        val operation = if (context == null) "fetch" else "save"
        AppLog.api("draft-sync", "$operation started")
        try {
            return block().also {
                error = (it as? Outcome.Err)?.telegramError
                if (error == null) AppLog.api("draft-sync", "$operation completed")
                else AppLog.warn("draft-sync", "$operation failed kind=${error?.kind}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.warn("draft-sync", "$operation failed unexpectedly")
            throw failure
        } finally {
            cache.draftSync.finish(context, token, error)
        }
    }

    private fun retryDelay(error: Outcome.Err): Long =
        maxOf(2_000L, (error.telegramError.retryAfterSeconds?.toLong() ?: 0) * 1_000L)
}
