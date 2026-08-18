package org.monogram.data.mtproto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.updates.DifferenceTooLong
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.tl.runtime.TlObject
import org.monogram.mtproto.transport.MtProtoRpcTransport
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateState

class MtProtoRoomUpdateRecoveryTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 2)
    private val initial = MtProtoUpdateState(MtProtoUpdateCursor(10, 20, 30, 40))

    @Test
    fun `completed difference acknowledges durable recovery marker`() = runBlocking {
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore().apply { enqueue(scope, UpdatesTooLong) }
        val adapter = MtProtoRoomUpdateRecovery(
            stateStore,
            MtProtoRoomLiveUpdateApplier(stateStore, pendingStore),
        )
        val session = adapter.open(
            scope,
            FakeTransport(DifferenceEmpty(31, 41)),
            applyEntities = {},
        ).openedSession()

        val result = session.recoverAndReplay { error("marker has no entities") }

        assertEquals(
            MtProtoRoomRecoveryResult.Completed(
                cursor = MtProtoUpdateCursor(10, 20, 31, 41),
                replay = MtProtoPendingReplayResult.Completed(1),
            ),
            result,
        )
        assertEquals(0, pendingStore.records.size)
        assertEquals(MtProtoUpdateCursor(10, 20, 31, 41), stateStore.state.cursor)
    }

    @Test
    fun `resync required retains durable recovery marker`() = runBlocking {
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore().apply { enqueue(scope, UpdatesTooLong) }
        val adapter = MtProtoRoomUpdateRecovery(
            stateStore,
            MtProtoRoomLiveUpdateApplier(stateStore, pendingStore),
        )
        val session = adapter.open(
            scope,
            FakeTransport(DifferenceTooLong(99)),
            applyEntities = {},
        ).openedSession()

        assertEquals(
            MtProtoRoomRecoveryResult.ResyncRequired,
            session.recoverAndReplay { error("must not replay") },
        )
        assertEquals(1, pendingStore.records.size)
        assertEquals(initial, stateStore.state)
    }

    @Test
    fun `failed difference transaction retains recovery marker`() = runBlocking {
        val failure = IllegalStateException("entity apply failed")
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore().apply { enqueue(scope, UpdatesTooLong) }
        val adapter = MtProtoRoomUpdateRecovery(
            stateStore,
            MtProtoRoomLiveUpdateApplier(stateStore, pendingStore),
        )
        val session = adapter.open(
            scope,
            FakeTransport(DifferenceEmpty(31, 41)),
            applyEntities = { throw failure },
        ).openedSession()

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { session.recoverAndReplay {} }
        }

        assertSame(failure, thrown)
        assertEquals(1, pendingStore.records.size)
        assertEquals(initial, stateStore.state)
    }

    @Test
    fun `live apply waits until recovery commit and replay complete`() = runBlocking {
        val recoveryEntered = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore().apply { enqueue(scope, UpdatesTooLong) }
        val applier = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)
        val session = MtProtoRoomUpdateRecovery(stateStore, applier).open(
            scope,
            FakeTransport {
                recoveryEntered.complete(Unit)
                releaseRecovery.await()
                DifferenceEmpty(31, 41)
            },
            applyEntities = {},
        ).openedSession()
        val recoveryJob = async { session.recoverAndReplay {} }
        recoveryEntered.await()

        val liveJob = async(start = CoroutineStart.UNDISPATCHED) {
            applier.apply(scope, envelope(pts = 11, seq = 42)) {}
        }
        assertFalse(liveJob.isCompleted)

        releaseRecovery.complete(Unit)
        assertEquals(
            MtProtoRoomRecoveryResult.Completed(
                cursor = MtProtoUpdateCursor(10, 20, 31, 41),
                replay = MtProtoPendingReplayResult.Completed(1),
            ),
            recoveryJob.await(),
        )
        assertEquals(true, liveJob.await() is MtProtoLiveUpdateApplyResult.Applied)
        assertEquals(MtProtoUpdateCursor(11, 20, 32, 42), stateStore.state.cursor)
    }

    private fun envelope(pts: Int, seq: Int) = UpdatesCombined(
        updates = listOf(UpdateDeleteMessages(emptyList(), pts, 1)),
        users = emptyList(),
        chats = emptyList(),
        date = 32,
        seqStart = seq,
        seq = seq,
    )

    private fun MtProtoRoomRecoveryOpenResult.openedSession() =
        (this as MtProtoRoomRecoveryOpenResult.Opened).session

    private class FakeStateStore(
        initial: MtProtoUpdateState,
    ) : MtProtoRecoveryStateStore, MtProtoTransactionalUpdateStateStore {
        var state = initial

        override suspend fun loadState(scope: MtProtoAuthKeyScope) = MtProtoUpdateStateLoadResult.Found(state)

        override suspend fun applyRecovery(
            scope: MtProtoAuthKeyScope,
            cursor: MtProtoUpdateCursor,
            applyEntities: suspend () -> Unit,
        ) {
            applyEntities()
            state = state.copy(cursor = cursor)
        }

        override suspend fun applyState(
            scope: MtProtoAuthKeyScope,
            state: MtProtoUpdateState,
            applyEntities: suspend () -> Unit,
        ) {
            applyEntities()
            this.state = state
        }
    }

    private class FakePendingStore : MtProtoPendingEnvelopeStore {
        val records = mutableListOf<MtProtoPendingEnvelope>()
        private var nextId = 1L

        override suspend fun enqueue(
            scope: MtProtoAuthKeyScope,
            envelope: Updates_faf6aaa3d5,
        ) = MtProtoPendingEnvelope.Decoded(nextId++, envelope).also(records::add)

        override suspend fun pending(scope: MtProtoAuthKeyScope) = records.toList()

        override suspend fun delete(sequenceId: Long) {
            records.removeAll { it.sequenceId == sequenceId }
        }

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }

    private class FakeTransport(
        private val response: suspend () -> TlObject,
    ) : MtProtoRpcTransport {
        constructor(response: TlObject) : this({ response })

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R = response() as R
        override fun close() = Unit
    }
}
