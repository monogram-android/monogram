package org.monogram.mtproto.updates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoChannelUpdateOrderingTest {
    private val cursor = MtProtoUpdateCursor(1, 2, 3, 4)

    @Test
    fun `advances independent channel cursors atomically`() = runBlocking {
        val applied = mutableListOf<MtProtoUpdateState>()
        val coordinator = MtProtoChannelUpdateOrderingCoordinator(
            MtProtoUpdateState(cursor, mapOf(100L to 10)),
        ) { applied += it }

        val expected = MtProtoUpdateState(cursor, mapOf(100L to 12, 200L to 1))
        assertEquals(
            MtProtoChannelUpdateOrderingResult.Applied(expected),
            coordinator.acceptBatch(
                listOf(
                    MtProtoChannelUpdateOrdering(100L, pts = 12, ptsCount = 2),
                    MtProtoChannelUpdateOrdering(200L, pts = 1, ptsCount = 1),
                )
            ),
        )
        assertEquals(listOf(expected), applied)
    }

    @Test
    fun `reports channel gap without applying batch`() = runBlocking {
        var applyCount = 0
        val initial = MtProtoUpdateState(cursor, mapOf(100L to 10))
        val coordinator = MtProtoChannelUpdateOrderingCoordinator(initial) { applyCount++ }

        assertEquals(
            MtProtoChannelUpdateOrderingResult.Gap(100L, expectedPts = 11, actualPts = 12),
            coordinator.accept(MtProtoChannelUpdateOrdering(100L, pts = 12, ptsCount = 1)),
        )
        assertEquals(0, applyCount)
        assertEquals(initial, coordinator.currentState())
    }

    @Test
    fun `does not acknowledge channel cursor when apply fails`() = runBlocking {
        val initial = MtProtoUpdateState(cursor, mapOf(100L to 10))
        val coordinator = MtProtoChannelUpdateOrderingCoordinator(initial) { error("commit failed") }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.accept(MtProtoChannelUpdateOrdering(100L, pts = 11, ptsCount = 1)) }
        }
        assertEquals(initial, coordinator.currentState())
    }
}
