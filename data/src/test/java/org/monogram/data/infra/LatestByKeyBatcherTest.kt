package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestByKeyBatcherTest {

    @Test
    fun `keeps only the latest value for each key in a burst`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val batches = mutableListOf<Map<Long, String>>()
        val batcher = LatestByKeyBatcher<Long, String>(scope, debounceMs = 0L) { batch ->
            batches += batch
        }

        assertTrue(batcher.offer(1L, "offline"))
        assertTrue(batcher.offer(1L, "online"))
        assertTrue(batcher.offer(2L, "recently"))
        advanceUntilIdle()

        assertEquals(listOf(mapOf(1L to "online", 2L to "recently")), batches)
        scope.cancel()
    }
}
