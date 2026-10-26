package org.monogram.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.models.GeoPlace
import org.monogram.core.models.LiveLocationPhase
import org.monogram.core.models.LiveLocationSession
import org.monogram.core.models.MessageId
import org.monogram.core.models.ServiceMedia
import org.monogram.network.bridge.MtprotoClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal suspend fun editRetainedLiveLocation(
    client: MtprotoClient,
    id: MessageId,
    point: GeoPlace?,
    isCurrent: () -> Boolean = { true },
): Outcome<Unit> {
    if (!isCurrent()) return Outcome.Err("RPC 500: INTERNAL")

    val result = client.editLiveLocation(
        id.chatId, id.id, point?.latitude, point?.longitude, point?.heading, stopped = point == null
    )

    val error = (result as? Outcome.Err)?.telegramError

    if (point != null || result is Outcome.Ok || !isCurrent() || error?.kind == TelegramError.Kind.Flood || error?.requiresReauth == true) return result

    val read = client.getMessage(id.chatId, id.id)
    val message = (read as? Outcome.Ok)?.value ?: return result
    val place = ServiceMedia.geo(message.fileName)

    return if (isCurrent() && message.id == id && message.outgoing && !message.pending && !message.failed && message.mediaKind == "geo" && place != null && (!place.live || place.periodSeconds <= 0)) Outcome.Ok(
        Unit

    ) else result
}

internal class LiveLocationSync(
    private val cache: OfflineWarmup,
    private val sample: suspend () -> GeoPlace?,
    private val permitted: () -> Boolean,
    private val edit: suspend (MessageId, GeoPlace?) -> Outcome<Unit>,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val rpc = Mutex()
    private val cycle = Mutex()
    private val lastAttempt = ConcurrentHashMap<MessageId, Long>()
    private val expiryElapsed = ConcurrentHashMap<MessageId, Long>()
    private val mutableErrors = MutableStateFlow<Map<MessageId, TelegramError>>(emptyMap())
    private val mutableFailure = MutableStateFlow<TelegramError?>(null)
    val failure: StateFlow<TelegramError?> = mutableFailure
    val errors: StateFlow<Map<MessageId, TelegramError>> = mutableErrors

    @Volatile
    private var foreground = false
    private val epoch = cache.outgoingAccountEpoch()
    private val acquisition =
        AtomicReference<Pair<MessageId, Deferred<GeoPlace?>>?>()
    private var job: Job? = null

    @Volatile
    private var blockedUntilElapsed = 0L

    suspend fun start(scope: CoroutineScope): Boolean {
        if (!cache.outgoingAccountIsCurrent(epoch)) return false
        if (job?.isActive == true) return mutableFailure.value == null
        mutableFailure.value = null
        try {
            cache.recoverLiveLocations(epoch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableFailure.value = TelegramError.parse("RPC 500: INTERNAL"); return false
        }
        job = scope.launch {
            try {
                while (isActive && cache.outgoingAccountIsCurrent(epoch)) {
                    syncOnce()
                    delay(15_000)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableFailure.value = TelegramError.parse("RPC 500: INTERNAL")
            } finally {
                withContext(NonCancellable) { safePause() }
            }
        }
        return job?.isActive == true
    }

    suspend fun stop() {
        foreground = false
        acquisition.get()?.second?.cancelAndJoin()
        job?.cancelAndJoin()
        job = null
        safePause()
    }

    suspend fun setForeground(value: Boolean) {
        foreground = value
        if (!value) {
            acquisition.get()?.second?.cancelAndJoin()
            safePause()
        }
    }

    suspend fun resume(id: MessageId): Boolean {
        if (!foreground || !permitted() || !cache.outgoingAccountIsCurrent(epoch) || blocked()) return false
        val current = cache.liveLocation(id, epoch) ?: return false
        if (current.phase != LiveLocationPhase.PAUSED || expired(current)) return false
        val changed =
            cache.transitionLiveLocation(id, current.revision, LiveLocationPhase.ACTIVE, epoch)
        if (changed) mutableErrors.update { it - id }
        return changed
    }

    suspend fun requestStop(id: MessageId): Boolean {
        if (!cache.outgoingAccountIsCurrent(epoch)) return false
        val current = cache.liveLocation(id, epoch) ?: return false
        if (current.phase !in setOf(
                LiveLocationPhase.ACTIVE, LiveLocationPhase.PAUSED, LiveLocationPhase.UNCERTAIN_STOP
            )
        ) return false
        if (!cache.transitionLiveLocation(
                id, current.revision, LiveLocationPhase.STOPPING, epoch
            )
        ) return false
        val claimedRevision = current.revision + 1
        try {
            acquisition.get()?.takeIf { it.first == id }?.second?.cancelAndJoin()
            return rpc.withLock {
                if (!cache.outgoingAccountIsCurrent(epoch)) return@withLock false
                val stopping = cache.liveLocation(id, epoch) ?: return@withLock false
                if (stopping.phase != LiveLocationPhase.STOPPING || stopping.revision != claimedRevision) return@withLock false
                if (blocked()) {
                    cache.transitionLiveLocation(
                        id, stopping.revision, LiveLocationPhase.UNCERTAIN_STOP, epoch
                    )
                    return@withLock false
                }
                val result = try {
                    edit(id, null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Outcome.Err("RPC 500: INTERNAL")
                }
                when (result) {
                    is Outcome.Ok -> {
                        cache.transitionLiveLocation(
                            id, stopping.revision, LiveLocationPhase.STOPPED, epoch
                        )
                        mutableErrors.update { it - id }
                        true
                    }

                    is Outcome.Err -> {
                        failed(id, result.telegramError)
                        cache.transitionLiveLocation(
                            id, stopping.revision, LiveLocationPhase.UNCERTAIN_STOP, epoch
                        )
                        false
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (cache.outgoingAccountIsCurrent(epoch)) cache.liveLocation(id, epoch)?.let {
                    if (it.phase == LiveLocationPhase.STOPPING && it.revision == claimedRevision) cache.transitionLiveLocation(
                        id, it.revision, LiveLocationPhase.UNCERTAIN_STOP, epoch
                    )
                }
            }
        }
    }

    internal suspend fun syncOnce() = cycle.withLock {
        if (!cache.outgoingAccountIsCurrent(epoch)) return@withLock
        val sessions = cache.observeLiveLocations().first()
        for (session in sessions) {
            if (!cache.outgoingAccountIsCurrent(epoch)) return@withLock
            if (expired(session)) {
                cache.transitionLiveLocation(
                    session.messageId, session.revision, LiveLocationPhase.EXPIRED, epoch
                )
                continue
            }
            if (session.phase != LiveLocationPhase.ACTIVE) continue
            if (!foreground || !permitted()) {
                pauseActive(); return@withLock
            }
            val previous = lastAttempt[session.messageId]
            if (blocked() || (previous != null && elapsedMillis() - previous < 15_000)) continue
            val point = acquire(session.messageId)
            rpc.withLock send@{
                if (!cache.outgoingAccountIsCurrent(epoch)) return@send
                val current = cache.liveLocation(session.messageId, epoch) ?: return@send
                if (current.revision != session.revision || current.phase != LiveLocationPhase.ACTIVE || blocked()) return@send
                if (!foreground || !permitted()) {
                    pauseActive(); return@send
                }
                if (expired(current)) {
                    cache.transitionLiveLocation(
                        current.messageId, current.revision, LiveLocationPhase.EXPIRED, epoch
                    )
                    return@send
                }
                if (point == null || !point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0 || (point.heading != null && point.heading !in 1..360)) {
                    cache.transitionLiveLocation(
                        current.messageId, current.revision, LiveLocationPhase.PAUSED, epoch
                    )
                    return@send
                }
                lastAttempt[current.messageId] = elapsedMillis()
                val result = try {
                    edit(current.messageId, point)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Outcome.Err("RPC 500: INTERNAL")
                }
                if (result is Outcome.Err && cache.outgoingAccountIsCurrent(epoch)) {
                    failed(current.messageId, result.telegramError)
                    cache.transitionLiveLocation(
                        current.messageId, current.revision, LiveLocationPhase.PAUSED, epoch
                    )
                }
            }
        }
    }

    private fun expired(session: LiveLocationSession): Boolean {
        val deadline = session.expiresAt ?: return false
        val elapsedDeadline = expiryElapsed.computeIfAbsent(session.messageId) {
            elapsedMillis() + maxOf(0, Math.multiplyExact(deadline, 1000) - wallMillis())
        }
        return session.expired(wallMillis() / 1000) || elapsedMillis() >= elapsedDeadline
    }

    private suspend fun acquire(id: MessageId): GeoPlace? = coroutineScope {
        val child = async(start = CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(8_000) { sample() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        val token = id to child
        acquisition.set(token)
        try {
            if (!foreground || !permitted() || !cache.outgoingAccountIsCurrent(epoch) || cache.liveLocation(
                    id,
                    epoch
                )?.phase != LiveLocationPhase.ACTIVE
            ) child.cancel()
            child.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            acquisition.compareAndSet(token, null); child.cancel()
        }
    }

    private suspend fun blocked() =
        elapsedMillis() < blockedUntilElapsed || wallMillis() < cache.outgoingSendBlockedUntil()

    private suspend fun failed(id: MessageId, error: TelegramError) {
        mutableErrors.update { it + (id to error) }
        error.retryAfterSeconds?.takeIf { it > 0 }?.let { seconds ->
            val wait = seconds.toLong() * 1000
            blockedUntilElapsed = maxOf(blockedUntilElapsed, elapsedMillis() + wait)
            cache.blockScheduledQueueUntil(wallMillis() + wait, epoch)
        }
    }

    private suspend fun safePause() {
        try {
            pauseActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableFailure.value = TelegramError.parse("RPC 500: INTERNAL")
        }
    }

    private suspend fun pauseActive() {
        if (!cache.outgoingAccountIsCurrent(epoch)) return
        for (session in cache.observeLiveLocations().first()) {
            if (session.phase == LiveLocationPhase.ACTIVE && cache.outgoingAccountIsCurrent(epoch)) cache.transitionLiveLocation(
                session.messageId, session.revision, LiveLocationPhase.PAUSED, epoch
            )
        }
    }
}
