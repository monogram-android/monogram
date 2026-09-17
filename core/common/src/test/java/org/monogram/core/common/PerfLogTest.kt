package org.monogram.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfLogTest {
    @Test
    fun staysDisabledAndStillRunsTheBlockOnPlainJvm() {
        // No log.tag on a JVM unit test, so nothing may be logged or thrown.
        assertFalse(PerfLog.isEnabled())
        var ran = false
        val value = perfOp("unit") {
            ran = true
            7
        }
        assertEquals(7, value)
        assertTrue(ran)
        PerfLog.mark("unit", 1)
        PerfLog.event("unit")
        PerfLog.trace("history", "page", elapsedMs = 1, handle = 1, dispatchClass = 0, result = "ok")
        PerfLog.dump("unit")
    }

    @Test
    fun monotonicClockAdvances() {
        val first = PerfLog.nowMs()
        Thread.sleep(2)
        assertTrue(PerfLog.nowMs() >= first)
    }
}
