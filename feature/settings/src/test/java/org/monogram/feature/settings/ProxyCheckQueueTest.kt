package org.monogram.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.feature.settings.ui.ProxyScreenState
import org.monogram.feature.settings.ui.profileKey
import org.monogram.feature.settings.ui.withPing

class ProxyCheckQueueTest {
    @Test
    fun queuedChecksStayVisibleAndDoNotOverlap() = runTest {
        val release = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        var restores = 0
        val queue = ProxyCheckQueue(
            scope = this,
            check = {
                inFlight += 1
                maxInFlight = maxOf(maxInFlight, inFlight)
                release.await()
                inFlight -= 1
                12L
            },
            restore = { restores += 1 },
        )
        val results = mutableListOf<String>()
        assertTrue(queue.request("a") { results += "a:${it}" })
        assertTrue(queue.request("b") { results += "b:${it}" })
        testScheduler.advanceUntilIdle()
        assertEquals(setOf("a", "b"), queue.checking.value)
        assertEquals(1, maxInFlight)
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("a:12", "b:12"), results)
        assertEquals(2, restores)
        assertEquals(emptySet<String>(), queue.checking.value)
    }

    @Test
    fun duplicateRequestIsIgnored() = runTest {
        val gate = Mutex(locked = true)
        var calls = 0
        val queue = ProxyCheckQueue(
            scope = this,
            check = {
                calls += 1
                gate.lock()
                1L
            },
            restore = {},
        )
        assertTrue(queue.request("a") {})
        assertFalse(queue.request("a") {})
        testScheduler.advanceUntilIdle()
        assertEquals(1, calls)
        gate.unlock()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun failedCheckRestoresAndReportsNull() = runTest {
        var restores = 0
        val queue = ProxyCheckQueue(
            scope = this,
            check = { error("offline") },
            restore = { restores += 1 },
        )
        var result: Long? = 7
        assertTrue(queue.request("a") { result = it })
        testScheduler.advanceUntilIdle()
        assertEquals(null, result)
        assertEquals(1, restores)
        assertEquals(emptySet<String>(), queue.checking.value)
    }

    @Test
    fun exclusiveWaitsForTheRunningCheck() = runTest {
        val gate = Mutex(locked = true)
        var exclusiveRan = false
        val queue = ProxyCheckQueue(
            scope = this,
            check = {
                gate.lock()
                1L
            },
            restore = {},
        )
        queue.request("a") {}
        launch {
            queue.exclusive { exclusiveRan = true }
        }
        testScheduler.advanceUntilIdle()
        assertFalse(exclusiveRan)
        gate.unlock()
        testScheduler.advanceUntilIdle()
        assertTrue(exclusiveRan)
    }

    @Test
    fun pingRecordKeepsIdentityAndReplacesLatency() {
        val saved = ProxyScreenState(host = "proxy.example", port = "1080", password = "secret")
        assertEquals(saved.profileKey(), saved.copy(password = "other").profileKey())
        val healthy = saved.withPing(15, 99)
        assertEquals(15L, healthy.latencyMs)
        assertEquals(0, healthy.consecutiveFailures)
        assertEquals(99L, healthy.lastCheckedAt)
        assertEquals(saved.profileKey(), healthy.profileKey())
        val failed = healthy.withPing(null, 100)
        assertEquals(null, failed.latencyMs)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(100L, failed.lastCheckedAt)
    }
}
