package org.monogram.data.datasource.cache

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
class MessageCacheWriterTest {

    @Test
    fun `preserves mutation order and batches a burst`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val batches = mutableListOf<List<MessageCacheMutation>>()
        val writer = MessageCacheWriter(
            scope = scope,
            maxBatchSize = 3,
            applyBatch = { batch -> batches += batch }
        )
        val mutations = (1L..5L).map { messageId ->
            MessageCacheMutation.MarkRead(chatId = 10L, upToMessageId = messageId)
        }

        mutations.forEach { mutation -> assertTrue(writer.enqueue(mutation)) }
        advanceUntilIdle()

        assertEquals(listOf(3, 2), batches.map { it.size })
        assertEquals(mutations, batches.flatten())
        scope.cancel()
    }

    @Test
    fun `retries failed batch and drains ten thousand queued mutations`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val batches = mutableListOf<List<MessageCacheMutation>>()
        var attempts = 0
        val writer = MessageCacheWriter(
            scope = scope,
            maxBatchSize = 64,
            applyBatch = { batch ->
                attempts++
                if (attempts == 1) error("transient cache failure")
                batches += batch
            }
        )
        runCurrent()
        val mutations = (1L..10_000L).map { messageId ->
            MessageCacheMutation.MarkRead(chatId = 10L, upToMessageId = messageId)
        }

        mutations.forEach { mutation -> assertTrue(writer.enqueue(mutation)) }
        advanceUntilIdle()

        assertEquals(mutations, batches.flatten())
        assertEquals(1L, writer.stats.value.retries)
        assertEquals(0L, writer.stats.value.failures)
        assertEquals(mutations.size.toLong(), writer.stats.value.enqueued)
        assertEquals(0, writer.stats.value.pending)
        assertEquals(0L, writer.stats.value.dropped)
        assertTrue(writer.stats.value.batches < mutations.size)
        scope.cancel()
    }
}
