package org.monogram.mtproto.updates

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MtProtoUpdateOrderingTest {
    @Test
    fun `accepts contiguous pts and advances after apply`() = runBlocking {
        val applied = mutableListOf<MtProtoUpdateCursor>()
        val coordinator = MtProtoUpdateOrderingCoordinator(MtProtoUpdateCursor(10, 20, 30, 40)) {
            applied += it
        }

        assertEquals(
            MtProtoUpdateOrderingResult.Applied(MtProtoUpdateCursor(12, 20, 31, 40)),
            coordinator.accept(MtProtoUpdateOrdering(pts = 12, ptsCount = 2, date = 31)),
        )
        assertEquals(listOf(MtProtoUpdateCursor(12, 20, 31, 40)), applied)
    }

    @Test
    fun `reports duplicate and gap without invoking apply`() = runBlocking {
        var applyCount = 0
        val coordinator = MtProtoUpdateOrderingCoordinator(MtProtoUpdateCursor(10, 20, 30, 40)) {
            applyCount++
        }

        assertEquals(MtProtoUpdateOrderingResult.Duplicate, coordinator.accept(MtProtoUpdateOrdering(pts = 10, ptsCount = 1)))
        assertEquals(
            MtProtoUpdateOrderingResult.Gap(11, 13, null, null),
            coordinator.accept(MtProtoUpdateOrdering(pts = 13, ptsCount = 1)),
        )
        assertEquals(0, applyCount)
        assertEquals(MtProtoUpdateCursor(10, 20, 30, 40), coordinator.currentCursor())
    }

    @Test
    fun `does not acknowledge cursor when apply fails`() = runBlocking {
        val coordinator = MtProtoUpdateOrderingCoordinator(MtProtoUpdateCursor(10, 20, 30, 40)) {
            error("commit failed")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { coordinator.accept(MtProtoUpdateOrdering(pts = 11, ptsCount = 1)) }
        }
        assertEquals(MtProtoUpdateCursor(10, 20, 30, 40), coordinator.currentCursor())
    }

    @Test
    fun `reports out of order sequence envelope`() = runBlocking {
        val coordinator = MtProtoUpdateOrderingCoordinator(MtProtoUpdateCursor(10, 20, 30, 40)) {}

        assertEquals(
            MtProtoUpdateOrderingResult.Gap(null, null, null, null, expectedSeq = 41, actualSeqStart = 42),
            coordinator.accept(MtProtoUpdateOrdering(date = 31, seqStart = 42, seq = 43)),
        )
        assertEquals(MtProtoUpdateCursor(10, 20, 30, 40), coordinator.currentCursor())
    }

    @Test
    fun `applies mixed replay and fresh batch once`() = runBlocking {
        val applied = mutableListOf<MtProtoUpdateCursor>()
        val coordinator = MtProtoUpdateOrderingCoordinator(MtProtoUpdateCursor(10, 20, 30, 40)) {
            applied += it
        }

        assertEquals(
            MtProtoUpdateOrderingResult.Applied(MtProtoUpdateCursor(12, 20, 31, 41)),
            coordinator.acceptBatch(
                listOf(
                    MtProtoUpdateOrdering(pts = 10, ptsCount = 1),
                    MtProtoUpdateOrdering(pts = 11, ptsCount = 1),
                    MtProtoUpdateOrdering(pts = 12, ptsCount = 1, date = 31, seq = 41),
                )
            ),
        )
        assertEquals(listOf(MtProtoUpdateCursor(12, 20, 31, 41)), applied)
    }

    @Test
    fun `reports inconsistent mixed counter state as gap`() = runBlocking {
        val coordinator = MtProtoUpdateOrderingCoordinator(MtProtoUpdateCursor(10, 20, 30, 40)) {}

        assertEquals(
            MtProtoUpdateOrderingResult.Gap(11, 10, null, null, expectedSeq = 41, actualSeqStart = 41),
            coordinator.accept(MtProtoUpdateOrdering(pts = 10, ptsCount = 1, seq = 41)),
        )
    }
}
