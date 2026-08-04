package org.monogram.data.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConflatedSyncRequestQueueTest {

    @Test
    fun `keeps the latest request received while a sync is running`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val processed = mutableListOf<String>()
        val queue = ConflatedSyncRequestQueue(
            scope = scope,
            minIntervalMs = 0L
        ) { reason ->
            processed += reason
            if (reason == "first") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }

        assertTrue(queue.request("first"))
        runCurrent()
        firstStarted.await()

        assertTrue(queue.request("second"))
        assertTrue(queue.request("third"))
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("first", "third"), processed)
        scope.cancel()
    }
}
