package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AtomicSingleFlightTest {
    @Test
    fun `concurrent callers share one request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val singleFlight = AtomicSingleFlight<String, Int>(scope)
        var calls = 0

        val callers = List(100) {
            async(dispatcher) { singleFlight.execute("message") { ++calls } }
        }
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(List(100) { 1 }, callers.map { it.await() })
    }

    @Test
    fun `cancelled waiter does not evict active request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val singleFlight = AtomicSingleFlight<String, Int>(scope)
        val result = CompletableDeferred<Int>()
        var calls = 0

        val owner = async(dispatcher) {
            singleFlight.execute("message") {
                calls++
                result.await()
            }
        }
        val cancelledWaiter = async(dispatcher) {
            singleFlight.execute("message") { error("must share owner request") }
        }
        testScheduler.runCurrent()
        cancelledWaiter.cancelAndJoin()
        val lateWaiter = async(dispatcher) {
            singleFlight.execute("message") { error("cancelled waiter evicted active request") }
        }
        testScheduler.runCurrent()

        assertEquals(1, calls)
        result.complete(42)
        advanceUntilIdle()
        assertEquals(42, owner.await())
        assertEquals(42, lateWaiter.await())
    }

    @Test
    fun `failed request is removed before retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val singleFlight = AtomicSingleFlight<String, Int>(scope)
        var calls = 0

        val failure = runCatching {
            singleFlight.execute("message") {
                calls++
                error("failed")
            }
        }.exceptionOrNull()
        val retry = singleFlight.execute("message") { ++calls }

        assertTrue(failure is IllegalStateException)
        assertEquals(2, retry)
        assertEquals(2, calls)
    }
}
