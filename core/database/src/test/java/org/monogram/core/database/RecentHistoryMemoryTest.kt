package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class RecentHistoryMemoryTest {
    @Test
    fun mutationsInvalidateAndAccountResetClearsRememberedHistory() =
        kotlinx.coroutines.runBlocking {
            val warmup = OfflineWarmup()
            val memory = warmup.recentHistory
            memory.remember(5, listOf(message(5, 1, "t1")))
            warmup.deleteMessage(PeerId(5), 1)
            assertNull(memory.peek(5))
            memory.remember(5, listOf(message(5, 1, "t1")))
            warmup.upsertMessages(listOf(message(5, 1, "updated")))
            assertNull(memory.peek(5))
            memory.remember(5, listOf(message(5, 1, "t1")))
            warmup.clearAccountCache()
            assertNull(memory.peek(5))
            assertEquals(emptyList<String>(), memory.thumbKeys(5))
        }

    @Test
    fun keepsRecentWindowAndThumbKeys() {
        val memory = RecentHistoryMemory()
        val chatId = 5L
        val messages = (1..40).map { id ->
            message(chatId, id, thumb = if (id % 2 == 0) "t$id" else null)
        }
        memory.remember(chatId, messages)
        assertEquals((1..30).toList(), memory.peek(chatId)?.map { it.id.id })
        assertEquals((2..30 step 2).map { "t$it" }, memory.thumbKeys(chatId))
    }

    @Test
    fun evictsTheOldestChatPastTheCap() {
        val memory = RecentHistoryMemory()
        repeat(RECENT_HISTORY_CHATS + 1) { index ->
            memory.remember(index.toLong(), listOf(message(index.toLong(), 1, thumb = "t$index")))
        }
        assertNull(memory.peek(0))
        assertEquals(
            listOf("t$RECENT_HISTORY_CHATS"),
            memory.thumbKeys(RECENT_HISTORY_CHATS.toLong())
        )
    }

    private fun message(chatId: Long, id: Int, thumb: String?) = Message(
        id = MessageId(PeerId(chatId), id),
        senderId = null,
        text = "m$id",
        date = id.toLong(),
        outgoing = false,
        thumbCacheKey = thumb,
    )
}
