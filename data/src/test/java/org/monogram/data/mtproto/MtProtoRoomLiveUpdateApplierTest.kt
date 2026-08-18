package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateState

class MtProtoRoomLiveUpdateApplierTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 2)
    private val initial = MtProtoUpdateState(MtProtoUpdateCursor(10, 20, 30, 40))

    @Test
    fun `durably enqueues before committing and removes after commit`() = runBlocking {
        val events = mutableListOf<String>()
        val stateStore = FakeStateStore(initial, events)
        val pendingStore = FakePendingStore(events)
        val applier = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)

        val result = applier.apply(scope, envelope(pts = 11, seq = 41)) { events += "entities" }

        val expected = MtProtoUpdateState(MtProtoUpdateCursor(11, 20, 31, 41))
        assertEquals(MtProtoLiveUpdateApplyResult.Applied(expected), result)
        assertEquals(expected, stateStore.state)
        assertEquals(listOf("enqueue", "entities", "state", "delete"), events)
        assertEquals(0, pendingStore.records.size)
    }

    @Test
    fun `removes duplicate but retains gap and recovery envelopes`() = runBlocking {
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore()
        val applier = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)
        var entitiesApplied = 0

        assertEquals(MtProtoLiveUpdateApplyResult.Duplicate, applier.apply(scope, envelope(10, 40)) { entitiesApplied++ })
        assertEquals(
            true,
            applier.apply(scope, envelope(12, 41)) { entitiesApplied++ } is MtProtoLiveUpdateApplyResult.Gap,
        )
        assertEquals(
            MtProtoLiveUpdateApplyResult.RecoveryRequired,
            applier.apply(scope, UpdatesTooLong) { entitiesApplied++ },
        )
        assertEquals(0, entitiesApplied)
        assertEquals(initial, stateStore.state)
        assertEquals(2, pendingStore.records.size)
    }

    @Test
    fun `retains pending envelope when entity transaction fails`() = runBlocking {
        val failure = IllegalStateException("entity apply failed")
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore()
        val applier = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { applier.apply(scope, envelope(11, 41)) { throw failure } }
        }

        assertEquals(failure, thrown)
        assertEquals(initial, stateStore.state)
        assertEquals(1, pendingStore.records.size)
    }

    @Test
    fun `replays durable envelope after applier restart`() = runBlocking {
        val stateStore = FakeStateStore(initial)
        val pendingStore = FakePendingStore()
        pendingStore.enqueue(scope, envelope(11, 41))
        val restarted = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)
        var entitiesApplied = 0

        val result = restarted.replayPending(scope) { entitiesApplied++ }

        assertEquals(MtProtoPendingReplayResult.Completed(1), result)
        assertEquals(1, entitiesApplied)
        assertEquals(0, pendingStore.records.size)
    }

    @Test
    fun `stops replay at corrupt durable envelope without deleting it`() = runBlocking {
        val pendingStore = FakePendingStore().apply { records += MtProtoPendingEnvelope.Corrupt(7) }
        val applier = MtProtoRoomLiveUpdateApplier(FakeStateStore(initial), pendingStore)

        val result = applier.replayPending(scope) { error("must not apply") }

        assertEquals(
            MtProtoPendingReplayResult.Blocked(0, MtProtoLiveUpdateApplyResult.CorruptEnvelope(7)),
            result,
        )
        assertEquals(listOf(MtProtoPendingEnvelope.Corrupt(7)), pendingStore.records)
    }

    private fun envelope(pts: Int, seq: Int) = UpdatesCombined(
        updates = listOf(UpdateDeleteMessages(emptyList(), pts, 1)),
        users = emptyList(),
        chats = emptyList(),
        date = 31,
        seqStart = seq,
        seq = seq,
    )

    private class FakeStateStore(
        initial: MtProtoUpdateState,
        private val events: MutableList<String> = mutableListOf(),
    ) : MtProtoTransactionalUpdateStateStore {
        var state = initial

        override suspend fun loadState(scope: MtProtoAuthKeyScope) = MtProtoUpdateStateLoadResult.Found(state)

        override suspend fun applyState(
            scope: MtProtoAuthKeyScope,
            state: MtProtoUpdateState,
            applyEntities: suspend () -> Unit,
        ) {
            applyEntities()
            this.state = state
            events += "state"
        }
    }

    private class FakePendingStore(
        private val events: MutableList<String> = mutableListOf(),
    ) : MtProtoPendingEnvelopeStore {
        val records = mutableListOf<MtProtoPendingEnvelope>()
        private var nextId = 1L

        override suspend fun enqueue(
            scope: MtProtoAuthKeyScope,
            envelope: Updates_faf6aaa3d5,
        ): MtProtoPendingEnvelope.Decoded {
            events += "enqueue"
            return MtProtoPendingEnvelope.Decoded(nextId++, envelope).also(records::add)
        }

        override suspend fun pending(scope: MtProtoAuthKeyScope) = records.toList()

        override suspend fun delete(sequenceId: Long) {
            records.removeAll { it.sequenceId == sequenceId }
            events += "delete"
        }

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }
}
