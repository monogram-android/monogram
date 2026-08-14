package org.monogram.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageReadBatcherTest {
    @Test
    fun `coalesces and deduplicates visible messages per chat`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + SupervisorJob())

        data class Batch(val chatId: Long, val threadId: Long?, val ids: LongArray)

        val batches = mutableListOf<Batch>()
        val batcher = MessageReadBatcher(
            scope = scope,
            context = dispatcher,
            flushDelayMs = 30L,
            flush = { chatId, threadId, ids -> batches += Batch(chatId, threadId, ids) }
        )

        batcher.enqueue(10L, listOf(1L, 2L))
        batcher.enqueue(10L, listOf(2L, 3L))
        batcher.enqueue(20L, listOf(7L))
        runCurrent()
        advanceTimeBy(30L)
        runCurrent()

        assertEquals(2, batches.size)
        assertArrayEquals(longArrayOf(1L, 2L, 3L), batches.first { it.chatId == 10L }.ids)
        assertArrayEquals(longArrayOf(7L), batches.first { it.chatId == 20L }.ids)
    }

    @Test
    fun `splits one scope into view message batches of at most one hundred ids`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + SupervisorJob())
        val batches = mutableListOf<LongArray>()
        val batcher = MessageReadBatcher(
            scope = scope,
            context = dispatcher,
            flushDelayMs = 30L,
            flush = { _, _, ids -> batches += ids }
        )

        batcher.enqueue(10L, (1L..205L).toList(), threadId = 42L)
        runCurrent()
        advanceTimeBy(30L)
        runCurrent()

        assertEquals(listOf(100, 100, 5), batches.map(LongArray::size))
        assertEquals((1L..205L).toList(), batches.flatMap { it.toList() })
        assertTrue(batches.all { it.size <= 100 })
    }

    @Test
    fun `default debounce waits one hundred fifty milliseconds`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + SupervisorJob())
        val batches = mutableListOf<LongArray>()
        val batcher = MessageReadBatcher(
            scope = scope,
            context = dispatcher,
            flush = { _, _, ids -> batches += ids }
        )

        batcher.enqueue(10L, listOf(1L, 2L))
        runCurrent()
        advanceTimeBy(149L)
        runCurrent()
        assertTrue(batches.isEmpty())

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, batches.size)
    }
}
