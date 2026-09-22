package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMediaFieldsTest {
    @Test
    fun mediaMetricsDefaultNull() {
        val message = Message(
            id = MessageId(PeerId(1), 1),
            senderId = null,
            text = "hi",
            date = 0L,
            outgoing = true,
        )
        assertNull(message.mediaDuration)
        assertNull(message.mediaWidth)
        assertNull(message.mediaHeight)
        assertNull(message.mediaKind)
    }

    @Test
    fun mediaMetricsKeepAssignedValues() {
        val message = Message(
            id = MessageId(PeerId(1), 2),
            senderId = null,
            text = null,
            date = 0L,
            outgoing = false,
            mediaKind = "gif",
            mediaDuration = 3,
            mediaWidth = 320,
            mediaHeight = 240,
        )
        assertEquals("gif", message.mediaKind)
        assertEquals(3, message.mediaDuration)
        assertEquals(320, message.mediaWidth)
        assertEquals(240, message.mediaHeight)
    }

    @Test
    fun mergeLocalCacheKeepsScrollWhenNetworkOmitsIt() {
        val stored = Chat(
            id = PeerId(7),
            title = "Ada",
            dialogScrollMessageId = 42,
        )
        val incoming = Chat(id = PeerId(7), title = "Ada", unreadCount = 3)
        val withMentions = incoming.copy(unreadMentionsCount = 4, unreadReactionsCount = 2)
        assertEquals(4, withMentions.mergeLocalCache(stored).unreadMentionsCount)
        assertEquals(2, withMentions.mergeLocalCache(stored).unreadReactionsCount)
        val stale = incoming.copy(readInboxMaxId = 1, unreadMentionsCount = 0)
        val kept = Chat(
            id = PeerId(7),
            title = "Ada",
            readInboxMaxId = 9,
            unreadMentionsCount = 5,
            unreadReactionsCount = 3,
        )
        assertEquals(5, stale.mergeLocalCache(kept).unreadMentionsCount)
        assertEquals(3, stale.mergeLocalCache(kept).unreadReactionsCount)
        assertEquals(42, incoming.mergeLocalCache(stored).dialogScrollMessageId)
        assertEquals(9, incoming.copy(dialogScrollMessageId = 9).mergeLocalCache(stored).dialogScrollMessageId)
        assertNull(incoming.mergeLocalCache(null).dialogScrollMessageId)
    }

    @Test
    fun mergeLocalCacheKeepsOutgoingWhenLastMessageMatches() {
        val stored = Chat(
            id = PeerId(7),
            title = "Ada",
            lastMessageOutgoing = true,
            lastMessageId = 12,
            readOutboxMaxId = 12,
        )
        val same = Chat(
            id = PeerId(7),
            title = "Ada",
            unreadCount = 0,
            lastMessageId = 12,
            readOutboxMaxId = 12,
        )
        assertTrue(same.mergeLocalCache(stored).lastMessageOutgoing)
        val newer = same.copy(lastMessageId = 13)
        assertFalse(newer.mergeLocalCache(stored).lastMessageOutgoing)
        val flagged = newer.copy(lastMessageOutgoing = true)
        assertTrue(flagged.mergeLocalCache(stored).lastMessageOutgoing)
        val empty = same.copy(lastMessageId = 0)
        assertFalse(empty.mergeLocalCache(stored).lastMessageOutgoing)
    }

    @Test
    fun mergeLocalCacheKeepsLeftOnPlaceholderNetworkRow() {
        val stored = Chat(id = PeerId(-100), title = "Team", isGroup = true, left = true)
        val incoming = Chat(id = PeerId(-100), title = "Chat -100", isGroup = true, left = false)
        assertTrue(incoming.mergeLocalCache(stored).left)
        assertFalse(
            Chat(id = PeerId(-100), title = "Team", isGroup = true, left = false)
                .mergeLocalCache(stored)
                .left,
        )
    }
}
