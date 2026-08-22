package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteChannelMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_faf6aaa3d5
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateState

class MtProtoRoomLiveUpdateApplierChannelIsolationTest {
    private fun channelEnvelope(channelId: Long, pts: Int, ptsCount: Int = 1): Updates_faf6aaa3d5 =
        UpdatesCombined(
            updates = listOf(UpdateDeleteChannelMessages(channelId, emptyList(), pts, ptsCount)),
            users = emptyList(),
            chats = emptyList(),
            date = 1,
            seqStart = 1,
            seq = 1,
        )

    @Test
    fun `channel gap defers only its own chain and independent channels apply`() = runBlocking {
        val stateStore = FakeTransactionalStore(
            MtProtoUpdateState(
                cursor = MtProtoUpdateCursor(10, 10, 1, 1),
                channelPts = mapOf(100L to 4, 200L to 8),
            ),
        )
        val pendingStore = RecordingPendingStore()
        val applier = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)

        // Channel 200 is one ahead (gap); channel 100 is in order and applies independently.
        val gapEnvelope = pendingStore.enqueueManual(scope(), channelEnvelope(200, pts = 10))
        val okEnvelope = pendingStore.enqueueManual(scope(), channelEnvelope(100, pts = 5))

        val result = applier.replayPending(scope()) { }

        // Deferred channel-gap envelope stays queued for post-recovery replay.
        assertTrue(result is MtProtoPendingReplayResult.Blocked)
        result as MtProtoPendingReplayResult.Blocked
        assertTrue(result.result is MtProtoLiveUpdateApplyResult.Gap)

        // The independent channel applied; the gapped one is retained.
        assertEquals(1, result.processedCount)
        assertTrue(pendingStore.contains(gapEnvelope.sequenceId))
        assertFalse(pendingStore.contains(okEnvelope.sequenceId))
    }

    @Test
    fun `all-in-order replay completes`() = runBlocking {
        val stateStore = FakeTransactionalStore(
            MtProtoUpdateState(MtProtoUpdateCursor(10, 10, 1, 1), channelPts = mapOf(100L to 0)),
        )
        val pendingStore = RecordingPendingStore()
        val applier = MtProtoRoomLiveUpdateApplier(stateStore, pendingStore)
        val first = pendingStore.enqueueManual(scope(), channelEnvelope(100, pts = 1))
        val second = pendingStore.enqueueManual(scope(), channelEnvelope(100, pts = 2))

        val result = applier.replayPending(scope()) { }

        assertTrue(result is MtProtoPendingReplayResult.Completed)
        assertEquals(false, pendingStore.contains(first.sequenceId))
        assertEquals(false, pendingStore.contains(second.sequenceId))
    }

    private fun scope() = MtProtoAuthKeyScope("default", MtProtoEnvironment.PRODUCTION, 2)

    private class FakeTransactionalStore(initial: MtProtoUpdateState) : MtProtoTransactionalUpdateStateStore {
        var state: MtProtoUpdateState = initial
        override suspend fun loadState(scope: MtProtoAuthKeyScope) = MtProtoUpdateStateLoadResult.Found(state)
        override suspend fun applyState(scope: MtProtoAuthKeyScope, state: MtProtoUpdateState, applyEntities: suspend () -> Unit) {
            applyEntities()
            this.state = state
        }
    }

    private class RecordingPendingStore : MtProtoPendingEnvelopeStore {
        val queued = mutableListOf<MtProtoPendingEnvelope.Decoded>()
        private val removed = mutableSetOf<Long>()
        private var nextId = 1L

        fun enqueueManual(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5): MtProtoPendingEnvelope.Decoded {
            val decoded = MtProtoPendingEnvelope.Decoded(nextId++, envelope)
            queued += decoded
            return decoded
        }

        fun contains(sequenceId: Long) = sequenceId !in removed &&
            queued.any { it.sequenceId == sequenceId }

        override suspend fun enqueue(scope: MtProtoAuthKeyScope, envelope: Updates_faf6aaa3d5): MtProtoPendingEnvelope.Decoded =
            enqueueManual(scope, envelope)

        override suspend fun pending(scope: MtProtoAuthKeyScope) =
            queued.filter { it.sequenceId !in removed }.sortedBy { it.sequenceId }

        override suspend fun delete(sequenceId: Long) {
            removed += sequenceId
        }

        override suspend fun deleteScope(scope: MtProtoAuthKeyScope) = Unit

        override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
    }
}
