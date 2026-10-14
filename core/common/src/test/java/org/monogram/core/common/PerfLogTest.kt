package org.monogram.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    @Test
    fun opIdsArePositiveAndUnique() {
        val first = PerfLog.newOpId()
        val second = PerfLog.newOpId()
        assertTrue(first > 0 && second > 0)
        assertTrue(first != second)
    }

    @Test
    fun opIdSurvivesDispatcherHopsAndIsRestored() {
        val trace = PerfLog.OpTrace()
        assertEquals(0L, PerfLog.currentOpId())
        val seen = runBlocking(trace) {
            val inherited = PerfLog.currentOpId()
            val hopped = withContext(Dispatchers.Default) { PerfLog.currentOpId() }
            inherited to hopped
        }
        assertEquals(trace.opId, seen.first)
        assertEquals(trace.opId, seen.second)
        assertEquals(0L, PerfLog.currentOpId())
    }

    @Test
    fun withOpIdRestoresPreviousValue() {
        PerfLog.withOpId(5L) {
            assertEquals(5L, PerfLog.currentOpId())
            PerfLog.withOpId(9L) { assertEquals(9L, PerfLog.currentOpId()) }
            assertEquals(5L, PerfLog.currentOpId())
        }
        assertEquals(0L, PerfLog.currentOpId())
    }
}
