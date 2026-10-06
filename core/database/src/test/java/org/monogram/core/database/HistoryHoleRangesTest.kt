package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.database.entity.MessageHoleEntity

class HistoryHoleRangesTest {
    @Test
    fun partialCoverageSplitsRatherThanErasingTheHole() {
        val hole = MessageHoleEntity(7, 1, 100)
        assertEquals(
            listOf(hole.copy(endId = 29), hole.copy(startId = 61)),
            subtractHistoryRange(hole, 30, 60)
        )
        assertEquals(emptyList<MessageHoleEntity>(), subtractHistoryRange(hole, 1, 100))
        assertEquals(listOf(hole), subtractHistoryRange(hole, 101, 200))
    }

    @Test
    fun maximumEndpointDoesNotOverflow() {
        val hole = MessageHoleEntity(7, 1, Int.MAX_VALUE)
        assertEquals(listOf(hole.copy(endId = 9)), subtractHistoryRange(hole, 10, Int.MAX_VALUE))
        assertEquals(listOf(hole), mergeHistoryHoles(listOf(hole, hole.copy(startId = 100))))
    }

    @Test
    fun adjacentAndOverlappingRangesMerge() {
        val hole = MessageHoleEntity(7, 1, 10)
        assertEquals(
            listOf(hole.copy(endId = 30), hole.copy(startId = 40, endId = 50)),
            mergeHistoryHoles(
                listOf(
                    hole.copy(startId = 40, endId = 50),
                    hole.copy(startId = 9, endId = 20), hole, hole.copy(startId = 21, endId = 30)
                )
            )
        )
    }
}
