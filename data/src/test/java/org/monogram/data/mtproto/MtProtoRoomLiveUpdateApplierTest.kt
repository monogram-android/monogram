package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesTooLong
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateState

class MtProtoRoomLiveUpdateApplierTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 2)
    private val initial = MtProtoUpdateState(MtProtoUpdateCursor(10, 20, 30, 40))

    @Test
    fun `commits entities and state together for contiguous envelope`() = runBlocking {
        val store = FakeStateStore(initial)
        val applier = MtProtoRoomLiveUpdateApplier(store)
        var entitiesApplied = 0

        val result = applier.apply(scope, envelope(pts = 11, seq = 41)) { entitiesApplied++ }

        val expected = MtProtoUpdateState(MtProtoUpdateCursor(11, 20, 31, 41))
        assertEquals(MtProtoLiveUpdateApplyResult.Applied(expected), result)
        assertEquals(expected, store.state)
        assertEquals(1, entitiesApplied)
    }

    @Test
    fun `does not invoke entity apply for duplicate gap or too long`() = runBlocking {
        val store = FakeStateStore(initial)
        val applier = MtProtoRoomLiveUpdateApplier(store)
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
        assertEquals(initial, store.state)
    }

    private fun envelope(pts: Int, seq: Int) = UpdatesCombined(
        updates = listOf(UpdateDeleteMessages(emptyList(), pts, 1)),
        users = emptyList(),
        chats = emptyList(),
        date = 31,
        seqStart = seq,
        seq = seq,
    )

    private class FakeStateStore(initial: MtProtoUpdateState) : MtProtoTransactionalUpdateStateStore {
        var state = initial

        override suspend fun loadState(scope: MtProtoAuthKeyScope) = MtProtoUpdateStateLoadResult.Found(state)

        override suspend fun applyState(
            scope: MtProtoAuthKeyScope,
            state: MtProtoUpdateState,
            applyEntities: suspend () -> Unit,
        ) {
            applyEntities()
            this.state = state
        }
    }
}
