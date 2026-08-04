package org.monogram.data.datasource.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderedEventFlowTest {

    @Test
    fun `preserves all terminal events in enqueue order`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val flow = OrderedEventFlow<Int>(scope)
        val received = mutableListOf<Int>()
        val collector = scope.launch {
            flow.events.take(128).collect(received::add)
        }
        runCurrent()

        repeat(128) { value -> assertTrue(flow.enqueue(value)) }
        advanceUntilIdle()

        assertEquals((0 until 128).toList(), received)
        collector.cancel()
        scope.cancel()
    }
}
