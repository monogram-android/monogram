package org.monogram.root

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.database.OfflineWarmup
import org.monogram.core.database.toModel
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.database.entity.OutgoingSendPhase
import org.monogram.core.database.entity.OutgoingTextSendEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import org.monogram.core.models.*

class OutgoingTextSyncTest {
    private fun row(id: Long, phase: OutgoingSendPhase = OutgoingSendPhase.READY) =
        OutgoingTextSendEntity(
            id,
            MessageEntity(
                chatId = id,
                id = -id.toInt(),
                senderId = 7,
                text = "fixture",
                date = 100,
                outgoing = true,
                mediaCacheKey = null,
                mediaKind = null,
                pending = true,
                randomId = id
            ),
            silent = true,
            noWebpage = true,
            scheduleDate = 0x7ffffffe,
            webpageUrl = null,
            phase = phase,
        )

    private fun sent(row: OutgoingTextSendEntity) = Outcome.Ok<OutgoingMessageResult>(
        OutgoingMessageResult.Sent(row.message.copy(id = 37, pending = false).toModel()),
    )

    private class Cache(rows: List<OutgoingTextSendEntity>) : OfflineWarmup() {
        val rows = MutableStateFlow(rows)
        var blockedUntil = 0L
        override fun observeOutgoingTextSends() = rows
        override suspend fun outgoingTextSends() = rows.value
        override suspend fun outgoingSendBlockedUntil() = blockedUntil
        override suspend fun blockOutgoingSendsUntil(deadline: Long) {
            blockedUntil = maxOf(blockedUntil, deadline)
        }

        override suspend fun claimOutgoingTextSend(randomId: Long): Boolean {
            while (true) {
                val before = rows.value
                if (before.none { it.randomId == randomId && it.phase == OutgoingSendPhase.READY }) return false
                if (rows.compareAndSet(
                        before,
                        before.map { if (it.randomId == randomId) it.copy(phase = OutgoingSendPhase.IN_FLIGHT) else it })
                ) return true
            }
        }

        override suspend fun recoverInterruptedTextSends() {
            rows.value =
                rows.value.map { if (it.phase == OutgoingSendPhase.IN_FLIGHT) it.copy(phase = OutgoingSendPhase.UNCERTAIN) else it }
        }

        override suspend fun markOutgoingTextSendUncertain(randomId: Long) {
            rows.value = rows.value.map {
                if (it.randomId == randomId && it.phase == OutgoingSendPhase.IN_FLIGHT) it.copy(
                    phase = OutgoingSendPhase.UNCERTAIN
                ) else it
            }
        }

        override suspend fun markOutgoingTextSendFailed(randomId: Long) {
            rows.value = rows.value.map {
                if (it.randomId == randomId && it.phase == OutgoingSendPhase.IN_FLIGHT) it.copy(
                    phase = OutgoingSendPhase.FAILED
                ) else it
            }
        }

        override suspend fun retryOutgoingTextSend(randomId: Long) {
            rows.value = rows.value.map {
                if (it.randomId == randomId && it.phase in setOf(
                        OutgoingSendPhase.UNCERTAIN,
                        OutgoingSendPhase.FAILED
                    )
                ) it.copy(phase = OutgoingSendPhase.READY) else it
            }
            outgoingSendSync.requestResume()
        }
    }

    @Test
    fun liveLocationPacingStopAndLifecycleFences() = runBlocking {
        withTimeout(10_000) {
            var wall = 100_000L
            var elapsed = 0L
            var permission = true
            var failStop = true
            var failMovement = false
            var blockedUntil = 0L
            val id = MessageId(PeerId(7), 37)
            val sessions = MutableStateFlow(listOf(LiveLocationSession(id, 123, 10000)))
            var recoveries = 0
            var recoveryFails = false
            val cache = object : OfflineWarmup() {
                override fun observeLiveLocations() = sessions
                override suspend fun liveLocation(id: MessageId, epoch: Long) =
                    sessions.value.firstOrNull { it.messageId == id }

                override suspend fun transitionLiveLocation(
                    id: MessageId,
                    revision: Long,
                    phase: LiveLocationPhase,
                    epoch: Long
                ): Boolean {
                    check(outgoingAccountIsCurrent(epoch))
                    val current = liveLocation(id, epoch) ?: return false
                    if (current.revision != revision || !current.canTransition(phase)) return false
                    sessions.update { rows ->
                        rows.map {
                            if (it == current) it.copy(
                                phase = phase,
                                revision = revision + 1
                            ) else it
                        }
                    }
                    return true
                }

                override suspend fun recoverLiveLocations(epoch: Long) {
                    recoveries++
                    if (recoveryFails) error("synthetic recovery failure")
                    sessions.update { rows ->
                        rows.map {
                            when (it.phase) {
                                LiveLocationPhase.ACTIVE -> it.copy(
                                    phase = LiveLocationPhase.PAUSED,
                                    revision = it.revision + 1
                                )

                                LiveLocationPhase.STOPPING -> it.copy(
                                    phase = LiveLocationPhase.UNCERTAIN_STOP,
                                    revision = it.revision + 1
                                )

                                else -> it
                            }
                        }
                    }
                }

                override suspend fun outgoingSendBlockedUntil() = blockedUntil
                override suspend fun blockOutgoingSendsUntil(deadline: Long) {
                    blockedUntil = maxOf(blockedUntil, deadline)
                }
            }
            val point = GeoPlace(1.0, 2.0)
            val calls = mutableListOf<String>()
            var pendingFix: CompletableDeferred<GeoPlace?>? = null
            var sampling: CompletableDeferred<Unit>? = null
            val worker = LiveLocationSync(
                cache,
                sample = { sampling?.complete(Unit); pendingFix?.await() ?: point },
                permitted = { permission },
                edit = { _, fix ->
                    calls += if (fix == null) "stop" else "move"
                    if (fix == null && failStop) Outcome.Err("rpc timeout")
                    else if (fix != null && failMovement) Outcome.Err("RPC 420: FLOOD_WAIT_30") else Outcome.Ok(
                        Unit
                    )
                },
                wallMillis = { wall },
                elapsedMillis = { elapsed })
            worker.setForeground(true)
            worker.syncOnce(); worker.syncOnce()
            assertEquals(listOf("move"), calls)
            elapsed = 14_999; worker.syncOnce(); assertEquals(1, calls.size)
            elapsed = 15_000; worker.syncOnce(); assertEquals(2, calls.size)
            elapsed = 30_000
            pendingFix = CompletableDeferred(); sampling = CompletableDeferred()
            val late = async { worker.syncOnce() }
            sampling.await()
            assertFalse(worker.requestStop(id))
            assertEquals(LiveLocationPhase.UNCERTAIN_STOP, sessions.value.single().phase)
            late.await()
            pendingFix.complete(point)
            assertEquals(listOf("move", "move", "stop"), calls)
            assertFalse(worker.resume(id))
            failStop = false
            assertTrue(worker.requestStop(id))
            worker.syncOnce(); assertEquals(4, calls.size)
            assertEquals(LiveLocationPhase.STOPPED, sessions.value.single().phase)
            assertFalse(worker.resume(id))
            sessions.value = listOf(LiveLocationSession(id, 123, 101))
            wall = 101_000; worker.syncOnce()
            assertEquals(LiveLocationPhase.EXPIRED, sessions.value.single().phase)
            sessions.value = listOf(LiveLocationSession(id, 123, 10000))
            permission = false; worker.syncOnce()
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            pendingFix = null; sampling = null; permission = true
            worker.syncOnce(); assertEquals(4, calls.size)
            assertTrue(worker.resume(id)); worker.syncOnce(); assertEquals(5, calls.size)
            worker.setForeground(false); worker.setForeground(true); worker.syncOnce()
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            assertEquals(5, calls.size)
            assertTrue(worker.resume(id))
            elapsed += 15_000; failMovement = true; worker.syncOnce()
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            assertNotNull(worker.errors.value[id])
            wall += 1_000_000
            assertFalse(worker.resume(id))
            elapsed += 30_000
            assertTrue(worker.resume(id))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            worker.start(scope)
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            worker.stop(); scope.cancel()
            cache.deactivateOutgoingAccount()
            val count = calls.size
            worker.syncOnce(); assertFalse(worker.requestStop(id)); assertEquals(count, calls.size)
            cache.activateOutgoingAccount()
            sessions.value = listOf(LiveLocationSession(id, 123, 10000))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val ordered = mutableListOf<String>()
            val serial =
                LiveLocationSync(cache, sample = { point }, permitted = { true }, edit = { _, fix ->
                    ordered += if (fix == null) "stop" else "move"
                    if (fix != null) {
                        entered.complete(Unit); release.await()
                    }
                    Outcome.Ok(Unit)
                }, wallMillis = { wall }, elapsedMillis = { elapsed })
            serial.setForeground(true)
            val moving = async { serial.syncOnce() }
            entered.await()
            val stopping = async { serial.requestStop(id) }
            while (sessions.value.single().phase != LiveLocationPhase.STOPPING) kotlinx.coroutines.yield()
            assertFalse(stopping.isCompleted)
            assertEquals(listOf("move"), ordered)
            release.complete(Unit); moving.await()
            assertTrue(stopping.await())
            serial.syncOnce()
            assertEquals(listOf("move", "stop"), ordered)
            val bounded = MessageId(id.chatId, 38)
            sessions.value = listOf(LiveLocationSession(bounded, 124, wall / 1000 + 10))
            serial.syncOnce()
            val beforeExpiry = ordered.size
            wall -= 1_000_000; elapsed += 10_000
            serial.syncOnce()
            assertEquals(LiveLocationPhase.EXPIRED, sessions.value.single().phase)
            assertEquals(beforeExpiry, ordered.size)
            var lookup: Message? = Message(
                id,
                null,
                null,
                100,
                outgoing = true,
                mediaKind = "geo",
                fileName = "{\"lat\":1.0,\"long\":2.0}"
            )
            var reads = 0
            var stopError = "rpc timeout"
            val client = java.lang.reflect.Proxy.newProxyInstance(
                org.monogram.network.bridge.MtprotoClient::class.java.classLoader,
                arrayOf(org.monogram.network.bridge.MtprotoClient::class.java)
            ) { _, method, args ->
                assertEquals(id.chatId, args!![0]); assertEquals(id.id, args[1])
                when (method.name) {
                    "editLiveLocation" -> Outcome.Err(stopError)
                    "getMessage" -> {
                        reads++; Outcome.Ok(lookup)
                    }

                    else -> error("unexpected ${method.name}")
                }
            } as org.monogram.network.bridge.MtprotoClient
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Ok)
            lookup = lookup!!.copy(id = MessageId(PeerId(8), 37))
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Err)
            lookup = lookup!!.copy(id = id, outgoing = false)
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Err)
            lookup = lookup!!.copy(
                outgoing = true,
                fileName = "{\"lat\":1.0,\"long\":2.0,\"live\":true,\"p\":900}"
            )
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Err)
            lookup = null
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Err)
            val beforeMove = reads
            assertTrue(editRetainedLiveLocation(client, id, point) is Outcome.Err)
            assertEquals(beforeMove, reads)
            stopError = "RPC 420: FLOOD_WAIT_30"
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Err)
            assertEquals(beforeMove, reads)
            stopError = "RPC 401: SESSION_REVOKED"
            assertTrue(editRetainedLiveLocation(client, id, null) is Outcome.Err)
            assertEquals(beforeMove, reads)
            assertTrue(
                editRetainedLiveLocation(
                    client,
                    id,
                    null,
                    isCurrent = { false }) is Outcome.Err
            )
            assertEquals(beforeMove, reads)
            val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var runtimeStopGate: CompletableDeferred<Unit>? = null
            var runtimeStopFails = false
            val runtime = LiveLocationRuntime(
                runtimeScope,
                cache,
                sample = { error("paused sessions must not acquire") },
                permitted = { true },
                edit = { _, _ ->
                    runtimeStopGate?.await()
                    if (runtimeStopFails) Outcome.Err("RPC 500: INTERNAL") else Outcome.Ok(Unit)
                })
            sessions.value = listOf(LiveLocationSession(id, 123, null))
            val first = Any();
            val second = Any()
            runtime.setForeground(first, true)
            val beforeStart = recoveries
            runtime.startAuthorized()
            assertEquals(beforeStart + 1, recoveries)
            sessions.value = listOf(LiveLocationSession(id, 123, null))
            runtime.setForeground(second, true); runtime.startAuthorized()
            runtime.setForeground(first, false)
            assertEquals(beforeStart + 1, recoveries)
            assertEquals(LiveLocationPhase.ACTIVE, sessions.value.single().phase)
            runtime.setForeground(second, false)
            kotlinx.coroutines.yield()
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            runtime.setForeground(first, true)
            kotlinx.coroutines.yield()
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            assertTrue(runtime.resume(id))
            assertEquals(LiveLocationPhase.ACTIVE, sessions.value.single().phase)
            runtimeStopGate = CompletableDeferred()
            runtimeStopFails = true
            val runtimeStopping = async { runtime.requestStop(id) }
            while (sessions.value.single().phase != LiveLocationPhase.STOPPING) kotlinx.coroutines.yield()
            runtime.setForeground(first, false)
            kotlinx.coroutines.yield()
            assertFalse(runtimeStopping.isCompleted)
            assertFalse(runtime.resume(id))
            runtimeStopGate!!.complete(Unit)
            assertFalse(runtimeStopping.await())
            assertEquals(LiveLocationPhase.UNCERTAIN_STOP, sessions.value.single().phase)
            assertNotNull(runtime.errors.first()[id])
            runtimeStopGate = null; runtimeStopFails = false
            assertTrue(runtime.requestStop(id))
            assertEquals(LiveLocationPhase.STOPPED, sessions.value.single().phase)
            assertTrue(runtime.errors.first().isEmpty())
            val previousEpoch = cache.outgoingAccountEpoch()
            cache.deactivateOutgoingAccount(); cache.activateOutgoingAccount()
            sessions.value = listOf(LiveLocationSession(id, 123, null, LiveLocationPhase.PAUSED))
            runtime.startAuthorized()
            assertFalse(runtime.resume(id, previousEpoch)); assertFalse(
            runtime.requestStop(
                id,
                previousEpoch
            )
        )
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            recoveryFails = true
            runtime.setForeground(first, true)
            kotlinx.coroutines.yield()
            assertNotNull(runtime.failure.first())
            assertFalse(runtime.resume(id, cache.outgoingAccountEpoch()))
            assertEquals(LiveLocationPhase.PAUSED, sessions.value.single().phase)
            recoveryFails = false
            assertTrue(runtime.resume(id, cache.outgoingAccountEpoch()))
            assertEquals(LiveLocationPhase.ACTIVE, sessions.value.single().phase)
            runtime.setForeground(first, false)
            runtime.stopAuthorized()
            assertFalse(runtime.resume(id)); assertFalse(runtime.requestStop(id))
            assertTrue(runtime.errors.first().isEmpty())
            runtimeScope.cancel()
        }
    }

    @Test
    fun preparationFailureIsNotUncertainAndDoesNotBlockOtherMessages() = runBlocking {
        val cache = Cache(listOf(row(1), row(2)))
        val attempted = mutableListOf<Long>()
        val worker = OutgoingTextSync(cache, {
            attempted += it.randomId
            if (it.randomId == 1L) throw OutgoingPreparationException(java.io.IOException("missing source"))
            sent(it)
        })
        worker.syncOnce()
        assertEquals(listOf(1L, 2L), attempted)
        assertEquals(OutgoingSendPhase.FAILED, cache.rows.value.first().phase)
        assertEquals(OutgoingSendPhase.IN_FLIGHT, cache.rows.value.last().phase)
        OutgoingTextSync(cache, { error("failed row must not automatically replay") }).syncOnce()
        cache.retryOutgoingTextSend(1)
        worker.syncOnce()
        assertEquals(listOf(1L, 2L, 1L), attempted)
    }

    @Test
    fun exceptionAfterSubmissionStillLeavesDeliveryUncertain() = runBlocking {
        val cache = Cache(listOf(row(1), row(2)))
        var calls = 0
        val worker = OutgoingTextSync(cache, {
            calls++
            throw java.io.IOException("connection lost after submission")
        })
        worker.syncOnce()
        assertEquals(1, calls)
        assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.first().phase)
        assertEquals(OutgoingSendPhase.READY, cache.rows.value.last().phase)
        worker.syncOnce()
        assertEquals(1, calls)
    }

    @Test
    fun readyIsClaimedOnceAndSuccessWaitsForTheDurableReceipt() = runBlocking {
        val cache = Cache(listOf(row(1), row(2, OutgoingSendPhase.UNCERTAIN)))
        val attempted = mutableListOf<OutgoingTextSendEntity>()
        val worker = OutgoingTextSync(cache, { attempted += it; sent(it) })
        worker.syncOnce(); worker.syncOnce(); worker.requestResume(); worker.syncOnce()
        assertEquals(listOf(1L), attempted.map { it.randomId })
        assertEquals(row(1), attempted.single())
        assertEquals(OutgoingSendPhase.IN_FLIGHT, cache.rows.value.first().phase)
        assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.last().phase)
    }

    @Test
    fun peerFailureIsIsolatedAndOnlyExplicitRetryMakesItReadyAgain() = runBlocking {
        val cache = Cache(listOf(row(1), row(2)))
        val attempted = mutableListOf<Long>()
        val worker = OutgoingTextSync(
            cache,
            {
                attempted += it.randomId; if (it.randomId == 1L) Outcome.Err("CHAT_WRITE_FORBIDDEN") else sent(
                it
            )
            })
        worker.syncOnce(); worker.syncOnce()
        assertEquals(listOf(1L, 2L), attempted)
        assertNotNull(cache.outgoingSendSync.state.value.errors[1])
        cache.retryOutgoingTextSend(1); worker.requestResume(); worker.syncOnce()
        assertEquals(listOf(1L, 2L, 1L), attempted)
    }

    @Test
    fun globalFailureStopsRemainingReadyRowsUntilResumeWithoutReplayingTheFailedRow() =
        runBlocking {
            val cache = Cache(listOf(row(1), row(2)))
            val attempted = mutableListOf<Long>()
            val worker = OutgoingTextSync(
                cache,
                {
                    attempted += it.randomId; if (it.randomId == 1L) Outcome.Err("RPC 500: INTERNAL") else sent(
                    it
                )
                })
            worker.syncOnce(); worker.syncOnce()
            assertEquals(listOf(1L), attempted)
            worker.requestResume(); worker.syncOnce()
            assertEquals(listOf(1L, 2L), attempted)
        }

    @Test
    fun floodWaitSurvivesRestartAndCannotBeShortenedByResumeOrAWallClockJump() = runBlocking {
        val cache = Cache(listOf(row(1), row(2)))
        var wall = 100_000L
        var elapsed = 1_000L
        val worker =
            OutgoingTextSync(cache, { Outcome.Err("FLOOD_WAIT_30") }, { wall }, { elapsed })
        assertEquals(30_000L, worker.syncOnce())
        worker.requestResume(); wall += 60_000
        assertEquals(30_000L, worker.syncOnce())
        wall = 110_000
        var count = 0
        val restored = OutgoingTextSync(cache, { count++; sent(it) }, { wall }, { elapsed })
        assertEquals(20_000L, restored.syncOnce())
        assertEquals(0, count)
        wall = 131_000; elapsed = 31_001
        assertEquals(0L, restored.syncOnce())
        assertEquals(1, count)
        assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.first().phase)
    }

    @Test
    fun stoppingJoinsTheNetworkAndLeavesAnUncertainAttemptWithoutAutomaticRestartReplay() =
        runBlocking {
            val cache = Cache(listOf(row(1)))
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val worker = OutgoingTextSync(cache, {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            })
            try {
                worker.start(scope)
                withTimeout(3_000) { entered.await() }
                worker.stop()
                assertTrue(cancelled.isCompleted)
                assertEquals(OutgoingSendPhase.UNCERTAIN, cache.rows.value.single().phase)
                assertNull(cache.outgoingSendSync.state.value.activeRandomId)
                val restored =
                    OutgoingTextSync(cache, { error("uncertain attempt must not replay") })
                assertEquals(0L, restored.syncOnce())
            } finally {
                worker.stop(); scope.cancel()
            }
        }

    @Test
    fun aReceiptBeforeTheCallbackPreventsAnErrorFromRecreatingTheAttempt() = runBlocking {
        val cache = Cache(listOf(row(1)))
        val worker = OutgoingTextSync(cache, {
            cache.rows.value = emptyList()
            Outcome.Err("INTERNAL")
        })
        worker.syncOnce()
        assertTrue(cache.rows.value.isEmpty())
        assertTrue(cache.outgoingSendSync.state.value.errors.isEmpty())
        assertNull(cache.outgoingSendSync.state.value.error)
    }
}
