package org.monogram.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WorkOwnershipTest {
    @Test
    fun oneReleaseDoesNotFinishWhileAnotherOwnerRemains() {
        val key = "ownership-${System.nanoTime()}"
        assertEquals(1, WorkOwnership.acquire(key, 1).consumers)
        assertEquals(2, WorkOwnership.acquire(key, 1).consumers)
        assertEquals(1, WorkOwnership.release(key)?.consumers)
        assertFalse(WorkOwnership.state(key)!!.terminal)
        WorkOwnership.finish(key)
        assertEquals(0, WorkOwnership.release(key)?.consumers)
        assertNull(WorkOwnership.state(key))
    }

    @Test
    fun promoteNeverLowersPriority() {
        val key = "promote-${System.nanoTime()}"
        WorkOwnership.acquire(key, 10)
        assertEquals(30, WorkOwnership.promote(key, 30))
        assertEquals(30, WorkOwnership.promote(key, 5))
        WorkOwnership.finish(key)
        WorkOwnership.release(key)
    }
}
