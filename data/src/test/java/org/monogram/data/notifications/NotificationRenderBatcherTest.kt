package org.monogram.data.notifications

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRenderBatcherTest {

    @Test
    fun `related updates render each chat once in a batch`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val renders = mutableListOf<Set<Long>>()
        val batcher = NotificationRenderBatcher(scope, batchWindowMs = 16L) { renders += it }
        runCurrent()

        batcher.enqueue(setOf(10L, 20L))
        batcher.enqueue(setOf(20L, 30L))
        advanceUntilIdle()

        assertEquals(listOf(setOf(10L, 20L, 30L)), renders)
        scope.cancel()
    }
}
