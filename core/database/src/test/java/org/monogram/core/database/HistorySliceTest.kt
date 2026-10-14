package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.database.entity.MessageHoleEntity
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class HistorySliceTest {
    private val chat = PeerId(7)
    private fun row(id: Int) = Message(
        MessageId(chat, id),
        senderId = null,
        text = "t",
        date = id.toLong(),
        outgoing = false,
    )

    private fun hole(start: Int, end: Int) = MessageHoleEntity(chat.value, start, end)

    @Test
    fun anUninitializedCacheIsPartialAndUncovered() {
        val slice = historySliceOf(listOf(row(10)), false, emptyList(), 1, 10)
        assertEquals(listOf(10), slice.rows.map { it.id.id })
        assertFalse(slice.covered)
        assertFalse(slice.fresh)
        assertFalse(slice.confirmedStart)
        assertFalse(slice.confirmedEnd)
    }

    @Test
    fun aHoleInsideTheRequestKeepsTheRowsButBlocksLocalSatisfaction() {
        val slice = historySliceOf(listOf(row(8), row(4)), true, listOf(hole(5, 7)), 4, 8)
        assertEquals(2, slice.rows.size)
        assertFalse(slice.covered)
        assertEquals(5L..7L, slice.holes.single())
    }

    @Test
    fun provenCoverageWithoutAHoleIsLocal() {
        val slice = historySliceOf(listOf(row(3), row(2), row(1)), true, emptyList(), 1, 3)
        assertTrue(slice.covered)
        assertTrue(slice.confirmedStart)
        assertTrue(slice.confirmedEnd)
    }

    @Test
    fun openEndsStayUnconfirmedWhileTheSentinelHolesRemain() {
        val slice = historySliceOf(
            listOf(row(20)),
            true,
            listOf(hole(1, 10), hole(30, Int.MAX_VALUE)),
            20,
            20,
        )
        assertTrue(slice.covered)
        assertFalse(slice.confirmedStart)
        assertFalse(slice.confirmedEnd)
    }

    @Test
    fun rowCountAloneNeverImpliesCoverage() {
        val slice = historySliceOf(List(100) { row(it + 1) }, false, emptyList(), 1, 100)
        assertFalse(slice.covered)
    }
}
