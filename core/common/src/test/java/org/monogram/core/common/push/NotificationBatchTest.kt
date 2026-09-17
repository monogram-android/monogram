package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBatchTest {
    private fun message(id: Int, text: String, at: Long = 0L) = NotificationMessage(id, text, at)

    @Test
    fun appendsMessagesInArrivalOrder() {
        val batch = NotificationBatch.append(emptyList(), message(1, "one"))
        val grown = NotificationBatch.append(batch, message(2, "two"))
        assertEquals(listOf("one", "two"), grown.map { it.text })
    }

    @Test
    fun repeatedMessageIdReplacesInsteadOfStacking() {
        val batch = listOf(message(1, "one"), message(2, "two"))
        val retried = NotificationBatch.append(batch, message(2, "two", at = 500L))
        assertEquals(2, retried.size)
        assertEquals(listOf(1, 2), retried.map { it.messageId })
        assertEquals(500L, retried.last().timestamp)
    }

    @Test
    fun keepsOnlyNewestMessagesWithinTheLimit() {
        var batch = emptyList<NotificationMessage>()
        repeat(12) { index -> batch = NotificationBatch.append(batch, message(index + 1, "msg $index"), max = 3) }
        assertEquals(listOf(10, 11, 12), batch.map { it.messageId })
    }

    @Test
    fun payloadsWithoutMessageIdDedupeOnTextAndTimestamp() {
        val batch = listOf(message(0, "joined", at = 10L))
        assertEquals(1, NotificationBatch.append(batch, message(0, "joined", at = 10L)).size)
        assertEquals(2, NotificationBatch.append(batch, message(0, "joined", at = 11L)).size)
    }

    @Test
    fun nonPositiveLimitDropsEverything() {
        assertEquals(emptyList<NotificationMessage>(), NotificationBatch.append(emptyList(), message(1, "one"), max = 0))
    }

    @Test
    fun appendKeepsOutgoingRepliesDistinctFromIncoming() {
        val reply = NotificationMessage(2, "и хорошо", 20L, outgoing = true)
        val batch = NotificationBatch.append(listOf(message(1, "Понял"), reply), message(3, "ok"))
        assertEquals(listOf(false, true, false), batch.map { it.outgoing })
    }

    @Test
    fun hiddenCountGrowsWithPushesAndKeepsQuietRepaints() {
        assertEquals(1, NotificationBatch.countHidden(previousCount = 0, isNewPush = true))
        assertEquals(4, NotificationBatch.countHidden(previousCount = 3, isNewPush = true))
        assertEquals(3, NotificationBatch.countHidden(previousCount = 3, isNewPush = false))
        // A repaint of a notification that never counted before still shows one message.
        assertEquals(1, NotificationBatch.countHidden(previousCount = 0, isNewPush = false))
    }
}
