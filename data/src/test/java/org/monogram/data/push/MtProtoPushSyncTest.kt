package org.monogram.data.push

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtProtoPushSyncTest {
    @Test
    fun `executes sync requests through the queue`() = runTest {
        val executed = mutableListOf<String>()
        val scope = CoroutineScope(backgroundScope.coroutineContext)
        val sync = MtProtoPushSync(scope, minIntervalMs = 0, execute = { reason -> executed += reason })

        assertTrue(sync.requestSync("fcm_message"))
        runCurrent()

        assertEquals(listOf("fcm_message"), executed)
    }

    @Test
    fun `conflates bursty pushes into one sync within the interval`() = runTest {
        val executed = mutableListOf<String>()
        val scope = CoroutineScope(backgroundScope.coroutineContext)
        val sync = MtProtoPushSync(scope, minIntervalMs = 0, execute = { reason -> executed += reason })

        // All three arrive before any runCurrent; CONFLATED keeps only the latest.
        assertTrue(sync.requestSync("push-1"))
        assertTrue(sync.requestSync("push-2"))
        assertTrue(sync.requestSync("push-3"))

        runCurrent()

        assertEquals(listOf("push-3"), executed)
    }

    @Test
    fun `sync failures are non-fatal and later requests still execute`() = runTest {
        var failFirst = true
        val executed = mutableListOf<String>()
        val scope = CoroutineScope(backgroundScope.coroutineContext)
        val sync = MtProtoPushSync(scope, minIntervalMs = 0, execute = { reason ->
            executed += reason
            if (failFirst) {
                failFirst = false
                throw IllegalStateException("network down")
            }
        })

        assertTrue(sync.requestSync("first"))
        runCurrent() // first fails; non-fatal
        assertTrue(sync.requestSync("second"))
        runCurrent()

        assertEquals(listOf("first", "second"), executed)
    }
}
