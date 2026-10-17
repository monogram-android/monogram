package org.monogram.network.bridge.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.common.telegram.TelegramError
import org.monogram.network.bridge.DurableUpdateBatch
import org.monogram.network.bridge.DurableUpdateSink
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor
import org.monogram.network.bridge.chat.toChatModels
import org.monogram.network.bridge.session.DispatchClass
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.bridge.session.dispatchClassName
import org.monogram.network.bridge.session.nativeRequest
import uniffi.monogram_mtproto.UpdateBatchDto
import uniffi.monogram_mtproto.UpdateDrainDto
import uniffi.monogram_mtproto.UpdateEventDto
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.monogram.network.bridge.chat.toModel as toFolderModel
import org.monogram.network.bridge.profile.toModel as toProfileModel

private const val EMITTED_WINDOW = 512
private const val REPLAY_WAIT_MS = 200L

internal class UpdatesPump(private val core: SessionCore) : UpdatesOps {
    private var premiumRefreshPending = false
    private var chatsRefreshPending = false
    private var foldersRefreshPending = false

    init {
        core.scope.launch { drainUpdatesLoop() }
    }

    override suspend fun getUpdatesState(): Outcome<UpdatesCursor> = core.coalesce("getUpdatesState") {
        when (val connected = core.ensureConnected()) {
            is Outcome.Err -> connected
            is Outcome.Ok -> core.rpc("getUpdatesState failed") { activeHandle ->
                val state = core.native.getUpdatesState(activeHandle)
                UpdatesCursor(
                    pts = state.pts,
                    qts = state.qts,
                    date = state.date,
                    seq = state.seq,
                )
            }
        }
    }

    private suspend fun drainUpdatesLoop() {
        var drainDelayMs = 25L
        var reconnectHandle = 0L
        val lastMetadataRefreshMs = AtomicLong(-1L)
        val lastChatsRefreshMs = AtomicLong(-1L)
        val lastPremiumRefreshMs = AtomicLong(-1L)
        val foldersInFlight = AtomicBoolean(false)
        val chatsInFlight = AtomicBoolean(false)
        val premiumInFlight = AtomicBoolean(false)
        var cooldownHandle = 0L
        fun cooldownDue(last: AtomicLong, windowMs: Long, now: Long): Boolean {
            val value = last.get()
            return value < 0L || now - value >= windowMs
        }
        while (true) {
            val activeHandle = core.activeHandleOrZero()
            if (activeHandle == 0L || core.sessionDead || core.connectedHandle != activeHandle) {
                if (activeHandle != 0L && !core.sessionDead && reconnectHandle == activeHandle) {
                    if (core.connect() is Outcome.Err) {
                        drainDelayMs = (drainDelayMs * 2).coerceIn(4_000L, 30_000L)
                        delay(drainDelayMs)
                    }
                    continue
                }
                reconnectHandle = 0L
                // `close()` shuts the wake channel down; a closed channel ends the loop
                // instead of failing this coroutine with an uncaught exception.
                try {
                    core.updatesWake.receive()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ClosedReceiveChannelException) {
                    return
                }
                continue
            }
            if (activeHandle != cooldownHandle) {
                cooldownHandle = activeHandle
                lastMetadataRefreshMs.set(-1L)
                lastChatsRefreshMs.set(-1L)
                lastPremiumRefreshMs.set(-1L)
            }
            val drain = try {
                if (!replayPendingBatches(activeHandle)) {
                    delay(drainDelayMs)
                    continue
                }
                val drainAt = if (PerfLog.isEnabled()) System.nanoTime() else 0L
                val drained = nativeRequest(
                    core.native,
                    core.nativeDispatcher,
                    DispatchClass.BACKGROUND_READ
                ) {
                    core.native.drainUpdateBatches(activeHandle)
                }
                val count = drained.batches.sumOf { it.events.size } + drained.liveEvents.size
                if (count > 0) {
                    PerfLog.noteUpdateDrain(count)
                }
                if (drainAt != 0L && count > 0) {
                    PerfLog.trace(
                        op = "updates",
                        phase = "drain",
                        elapsedMs = (System.nanoTime() - drainAt) / 1_000_000,
                        handle = activeHandle,
                        dispatchClass = DispatchClass.BACKGROUND_READ,
                        result = "ok",
                        detail = "count=$count batches=${drained.batches.size} lane=${
                            dispatchClassName(
                                DispatchClass.BACKGROUND_READ
                            )
                        }",
                    )
                }
                drainDelayMs = 25L
                reconnectHandle = 0L
                if (drained.replayRequired) {
                    core.replayPending.set(true)
                    if (core.durableSink == null) drainDelayMs = REPLAY_WAIT_MS
                }
                if (core.isCurrentHandle(activeHandle)) drained else UpdateDrainDto(
                    emptyList(),
                    emptyList(),
                    false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (core.isCurrentHandle(activeHandle)) {
                    val failure = core.fail(e, "drain updates failed")
                    if (failure.telegramError.kind == TelegramError.Kind.Network ||
                        failure.telegramError.kind == TelegramError.Kind.Internal
                    ) {
                        reconnectHandle = activeHandle
                    }
                    drainDelayMs = (drainDelayMs * 2).coerceIn(4_000L, 30_000L)
                }
                UpdateDrainDto(emptyList(), emptyList(), false)
            }
            for (batch in drain.batches) {
                deliverEvents(batch.events, batch.accountGeneration, batch.batchId)
            }
            deliverEvents(drain.liveEvents, 0L, 0L)
            for (batch in drain.batches) {
                rememberEmitted(batch)
                if (!commitAndAcknowledge(activeHandle, batch)) {
                    core.replayPending.set(true)
                    break
                }
            }
            // Chat metadata events carry no self-user flags. Coalesce the fallback
            // lookup after delivering messages and bound it during busy updates.
            val now = core.clock.elapsedMs()
            val refreshHandle = activeHandle
            if (foldersRefreshPending &&
                cooldownDue(lastMetadataRefreshMs, 2_000L, now) &&
                foldersInFlight.compareAndSet(false, true)
            ) {
                core.scope.launch {
                    try {
                        val folders = core.onNativeIfFree {
                            core.native.getFolders(it).map { dto -> dto.toFolderModel() }
                        }
                        if (folders != null && core.isCurrentHandle(refreshHandle)) {
                            lastMetadataRefreshMs.set(core.clock.elapsedMs())
                            foldersRefreshPending = false
                            core.updatesEvents.emit(MtprotoUpdate.FoldersChanged(folders))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val raw = when (e) {
                            is uniffi.monogram_mtproto.MtprotoException.Message -> e.v1
                            else -> e.message
                        }?.removePrefix("v1=")?.trim().orEmpty()
                        val telegram = TelegramError.parse(
                            raw.ifBlank { "refresh folders failed" },
                            "refresh folders failed",
                        )
                        val waitSec = if (telegram.kind == TelegramError.Kind.Flood) {
                            (telegram.retryAfterSeconds ?: telegram.argument ?: 3).coerceIn(3, 60)
                        } else {
                            core.fail(e, "refresh folders failed", degradeHome = false)
                            15
                        }
                        lastMetadataRefreshMs.set(
                            core.clock.elapsedMs() + (waitSec - 2).toLong() * 1_000L,
                        )
                    } finally {
                        foldersInFlight.set(false)
                    }
                }
            }
            if (chatsRefreshPending && core.isCurrentHandle(activeHandle) &&
                cooldownDue(lastChatsRefreshMs, 2_000L, now) &&
                chatsInFlight.compareAndSet(false, true)
            ) {
                core.scope.launch {
                    try {
                        val chats = core.onNativeIfFree { handle ->
                            core.native.getChats(handle).toChatModels()
                        }
                        if (chats != null && core.isCurrentHandle(refreshHandle)) {
                            lastChatsRefreshMs.set(core.clock.elapsedMs())
                            chatsRefreshPending = false
                            core.updatesEvents.emit(MtprotoUpdate.ChatsChanged(chats))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        core.fail(e, "refresh chats failed", degradeHome = false)
                        lastChatsRefreshMs.set(core.clock.elapsedMs() + 13_000L)
                    } finally {
                        chatsInFlight.set(false)
                    }
                }
            }
            if (premiumRefreshPending && core.isCurrentHandle(activeHandle) &&
                cooldownDue(lastPremiumRefreshMs, 60_000L, now) &&
                premiumInFlight.compareAndSet(false, true)
            ) {
                core.scope.launch {
                    val profile = try {
                        core.onNativeIfFree { handle ->
                            core.native.getProfile(handle, 0L).toProfileModel()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        lastPremiumRefreshMs.set(core.clock.elapsedMs())
                        core.fail(e, "refresh account failed", degradeHome = false)
                        null
                    } finally {
                        premiumInFlight.set(false)
                    }
                    if (profile != null && core.isCurrentHandle(refreshHandle)) {
                        lastPremiumRefreshMs.set(core.clock.elapsedMs())
                        premiumRefreshPending = false
                        core.updatesEvents.emit(MtprotoUpdate.AccountPremium(profile.isPremium))
                    }
                }
            }
            if (core.isCurrentHandle(activeHandle)) {
                delay(drainDelayMs)
            }
        }
    }

    private val emitted = LinkedHashSet<Pair<Long, Long>>()

    private fun rememberEmitted(batch: UpdateBatchDto) {
        emitted += batch.accountGeneration to batch.batchId
        while (emitted.size > EMITTED_WINDOW) emitted.remove(emitted.first())
    }

    /**
     * Commits one batch through the application sink, then acknowledges it. The
     * acknowledgement is only a native list removal; nothing here waits for Room
     * while a native lock is held. Returns false when the batch must stay queued.
     */
    private suspend fun commitAndAcknowledge(handle: Long, batch: UpdateBatchDto): Boolean {
        val sink = core.durableSink ?: return false
        val committed = try {
            sink.commit(
                DurableUpdateBatch(
                    accountGeneration = batch.accountGeneration,
                    batchId = batch.batchId,
                    updates = batch.events.flatMap { it.toMtprotoUpdates() },
                    cursor = batch.cursorAfter?.let {
                        UpdatesCursor(
                            it.pts,
                            it.qts,
                            it.date,
                            it.seq
                        )
                    },
                    channelCursors = batch.channelPtsAfter.associate { it.chatId to it.pts },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn("updates", "durable commit failed")
            false
        }
        if (!committed) return false
        nativeRequest(core.native, core.nativeDispatcher, DispatchClass.BACKGROUND_READ) {
            core.native.acknowledgeUpdateBatch(handle, batch.accountGeneration, batch.batchId)
        }
        return true
    }

    /**
     * Applies batches saved by an earlier run (or a failed commit) before any new
     * update is drained. Returns false when a batch could not be committed.
     */
    private suspend fun replayPendingBatches(handle: Long): Boolean {
        if (core.durableSink == null || !core.replayPending.get()) return true
        val pending =
            nativeRequest(core.native, core.nativeDispatcher, DispatchClass.BACKGROUND_READ) {
                core.native.pendingUpdateBatches(handle)
            }
        for (batch in pending) {
            if ((batch.accountGeneration to batch.batchId) !in emitted) {
                deliverEvents(batch.events, batch.accountGeneration, batch.batchId)
                rememberEmitted(batch)
            }
            if (!commitAndAcknowledge(handle, batch)) return false
        }
        core.replayPending.set(false)
        return true
    }

    private suspend fun deliverEvents(
        events: List<UpdateEventDto>,
        accountGeneration: Long,
        acceptedOrder: Long,
    ) {
        for (event in events) {
            when (event) {
                is UpdateEventDto.ChatsChanged -> {
                    chatsRefreshPending = true
                    premiumRefreshPending = true
                }

                is UpdateEventDto.FoldersChanged -> foldersRefreshPending = true
                else -> event.toMtprotoUpdates().forEach { update ->
                    val ordered = if (update is MtprotoUpdate.NewMessage) {
                        update.copy(
                            accountGeneration = accountGeneration,
                            acceptedOrder = acceptedOrder,
                        )
                    } else {
                        update
                    }
                    core.updatesEvents.emit(ordered)
                }
            }
        }
    }

    override fun installDurableUpdateSink(sink: DurableUpdateSink) {
        core.durableSink = sink
        core.replayPending.set(true)
        core.updatesWake.trySend(Unit)
    }

    override fun updates(): Flow<MtprotoUpdate> = merge(core.updatesEvents, core.localUpdates)
    override fun sessionLost(): Flow<Unit> = core.sessionLostFlow.asSharedFlow()
}
