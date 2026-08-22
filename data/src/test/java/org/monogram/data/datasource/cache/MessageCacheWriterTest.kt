package org.monogram.data.datasource.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.model.MessageWindowEntity
import org.monogram.data.db.model.MessageEntity
import org.monogram.domain.repository.ConversationKey
import org.monogram.domain.repository.ConversationScope

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

    @Test
    fun `enqueue and await returns only after durable batch completes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher + SupervisorJob())
        var applied = false
        val writer = MessageCacheWriter(
            scope = scope,
            applyBatch = { applied = true }
        )

        val caller = scope.launch {
            writer.enqueueAndAwait(MessageCacheMutation.MarkRead(10L, 20L))
            assertTrue(applied)
        }

        assertTrue(caller.isActive)
        advanceUntilIdle()
        assertTrue(caller.isCompleted)
        scope.cancel()
    }

    @Test
    fun `enqueue and await propagates terminal writer failure`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var attempts = 0
        var invalidated = false
        val writer = MessageCacheWriter(
            scope = scope,
            applyBatch = {
                attempts++
                error("database unavailable")
            },
            onTerminalFailure = { mutations, _ ->
                assertEquals(1, mutations.size)
                invalidated = true
            }
        )

        val failure = runCatching {
            writer.enqueueAndAwait(MessageCacheMutation.MarkRead(10L, 20L))
        }.exceptionOrNull()
        advanceUntilIdle()

        assertTrue(failure is IllegalStateException)
        assertEquals(3, attempts)
        assertEquals(2L, writer.stats.value.retries)
        assertEquals(1L, writer.stats.value.failures)
        assertTrue(invalidated)
        scope.cancel()
    }

    @Test
    fun `terminal history failure invalidates coverage but preserves viewport anchor`() = runTest {
        val source = InMemoryChatLocalDataSource()
        val window = MessageWindowEntity(
            chatId = 10L,
            scopeType = "thread",
            scopeId = 20L,
            oldestMessageId = 1L,
            newestMessageId = 2L,
            olderBoundaryReached = false,
            newerBoundaryReached = true,
            lastNetworkSyncAt = 30L,
            generation = 1L,
            protectedMessageId = 99L
        )
        source.upsertMessageWindow(window)

        invalidateFailedMessageCacheCoverage(
            localDataSource = source,
            mutations = listOf(MessageCacheMutation.UpdateWindow(window))
        )

        val invalidated = source.getMessageWindow(10L, "thread", 20L)
        assertEquals(null, invalidated?.oldestMessageId)
        assertEquals(null, invalidated?.newestMessageId)
        assertEquals(false, invalidated?.olderBoundaryReached)
        assertEquals(false, invalidated?.newerBoundaryReached)
        assertEquals(99L, invalidated?.protectedMessageId)
    }

    @Test
    fun `live mutations keep forum topic and message thread coverage separate`() = runTest {
        val source = InMemoryChatLocalDataSource()
        listOf("forum_topic", "message_thread", "thread").forEach { scopeType ->
            source.upsertMessageWindow(
                MessageWindowEntity(
                    chatId = 10L,
                    scopeType = scopeType,
                    scopeId = 20L,
                    oldestMessageId = 1L,
                    newestMessageId = 2L,
                    olderBoundaryReached = false,
                    newerBoundaryReached = true,
                    lastNetworkSyncAt = 30L,
                    generation = 1L
                )
            )
        }
        val message = MessageEntity(
            id = 1L,
            chatId = 10L,
            senderId = 1L,
            date = 1,
            content = "message",
            contentType = "text",
            isOutgoing = false,
            isRead = false,
            threadId = 20L
        )

        invalidateFailedMessageCacheCoverage(
            localDataSource = source,
            mutations = listOf(
                MessageCacheMutation.Persist(
                    message,
                    ConversationKey(10L, ConversationScope.ForumTopic(20L))
                ),
                MessageCacheMutation.Persist(
                    message.copy(id = 2L),
                    ConversationKey(10L, ConversationScope.MessageThread(20L))
                )
            )
        )

        assertEquals(null, source.getMessageWindow(10L, "forum_topic", 20L)?.oldestMessageId)
        assertEquals(null, source.getMessageWindow(10L, "message_thread", 20L)?.oldestMessageId)
        assertEquals(1L, source.getMessageWindow(10L, "thread", 20L)?.oldestMessageId)
    }

}
