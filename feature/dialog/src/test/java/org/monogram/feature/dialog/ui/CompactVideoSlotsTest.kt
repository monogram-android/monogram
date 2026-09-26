package org.monogram.feature.dialog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactVideoSlotsTest {
    @Test
    fun capsConcurrentCompactPlayers() {
        CompactVideoSlots.resetForTests()
        try {
            repeat(CompactVideoSlots.MAX) {
                assertTrue(CompactVideoSlots.tryAcquire())
            }
            assertFalse(CompactVideoSlots.tryAcquire())
            CompactVideoSlots.release()
            assertTrue(CompactVideoSlots.tryAcquire())
        } finally {
            CompactVideoSlots.resetForTests()
        }
    }
}
