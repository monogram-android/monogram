package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIncomingMergeTest {
    private val chatId = PeerId(42)
    private val chat =
        Chat(id = chatId, title = "c", lastMessageId = 10, unreadCount = 2, readInboxMaxId = 5)

    private fun message(id: Int, outgoing: Boolean = false, text: String = "m$id") =
        Message(
            MessageId(chatId, id),
            senderId = null,
            text = text,
            date = id.toLong(),
            outgoing = outgoing
        )

    @Test
    fun newerMessageUpdatesPreviewAndUnread() {
        val merged = chat.mergeIncomingMessage(message(11))
        assertEquals(11, merged.lastMessageId)
        assertEquals(3, merged.unreadCount)
    }

    @Test
    fun olderMessageKeepsTheTopPreviewButStillCounts() {
        val merged = chat.mergeIncomingMessage(message(8))
        assertEquals(10, merged.lastMessageId)
        assertEquals(chat.lastMessagePreview, merged.lastMessagePreview)
        assertEquals(3, merged.unreadCount)
    }

    @Test
    fun repeatedDeliveryDoesNotCountTwice() {
        val once = chat.mergeIncomingMessage(message(11))
        val twice = once.mergeIncomingMessage(message(11))
        assertEquals(once, twice)
        assertEquals(
            chat.unreadCount,
            chat.mergeIncomingMessage(message(8), alreadyStored = true).unreadCount
        )
    }

    @Test
    fun anOlderAcceptedOrderDoesNotReplaceTheRow() {
        val first = chat.mergeIncomingMessage(message(11), accountGeneration = 4, acceptedOrder = 2)
        val stale = first.mergeIncomingMessage(
            message(12),
            accountGeneration = 4,
            acceptedOrder = 1,
        )
        assertEquals(first, stale)
        val next = stale.mergeIncomingMessage(message(12), accountGeneration = 4, acceptedOrder = 3)
        assertEquals(12, next.lastMessageId)
    }

    @Test
    fun outgoingAndAlreadyReadMessagesDoNotAddUnread() {
        assertEquals(2, chat.mergeIncomingMessage(message(11, outgoing = true)).unreadCount)
        assertEquals(2, chat.mergeIncomingMessage(message(4)).unreadCount)
    }

    @Test
    fun mergeLocalCacheKeepsHigherReadCursorWhenStoredLastMessageIsNewer() {
        val stored = Chat(
            id = chatId,
            title = "c",
            lastMessageId = 30,
            readInboxMaxId = 10,
            readOutboxMaxId = 8,
        )
        val incoming = stored.copy(lastMessageId = 20, readInboxMaxId = 25, readOutboxMaxId = 4)
        val merged = incoming.mergeLocalCache(stored)
        assertEquals(25, merged.readInboxMaxId)
        assertEquals(8, merged.readOutboxMaxId)
    }

    @Test
    fun explicitUnmuteSurvivesAnInheritedDialogsRow() {
        val stored = Chat(
            id = chatId,
            title = "c",
            muted = false,
            muteOverride = true,
            lastMessageId = 12,
        )
        val merged = stored.copy(muteOverride = false).mergeLocalCache(stored)
        assertFalse(merged.muted)
        assertTrue(merged.muteOverride)
    }
}
