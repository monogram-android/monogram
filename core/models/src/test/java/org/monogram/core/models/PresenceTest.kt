package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceTest {
    @Test
    fun outgoingReadUsesOutboxCursor() {
        assertTrue(Presence.outgoingRead(true, false, 10, 10))
        assertTrue(Presence.outgoingRead(true, false, 9, 10))
        assertFalse(Presence.outgoingRead(true, false, 11, 10))
        assertFalse(Presence.outgoingRead(true, true, 10, 10))
        assertFalse(Presence.outgoingRead(false, false, 10, 10))
        assertFalse(Presence.outgoingRead(true, false, -1, 10))
    }

    @Test
    fun ticksCoverPendingSentReadFailed() {
        assertEquals("\u2026", Presence.ticks(pending = true, failed = false, read = false))
        assertEquals("\u2713", Presence.ticks(pending = false, failed = false, read = false))
        assertEquals("\u2713\u2713", Presence.ticks(pending = false, failed = false, read = true))
        assertEquals("!", Presence.ticks(pending = false, failed = true, read = false))
    }

    @Test
    fun chatListOmitsIncomingAndChannels() {
        assertEquals(
            null,
            Presence.chatListOutgoingMark(
                outgoing = false,
                isChannel = false,
                lastMessageId = 10,
                readInboxMaxId = 10,
                readOutboxMaxId = 10,
                unreadCount = 0,
            ),
        )
        assertEquals(
            null,
            Presence.chatListOutgoingMark(
                outgoing = true,
                isChannel = true,
                lastMessageId = 10,
                readInboxMaxId = 0,
                readOutboxMaxId = 10,
                unreadCount = 0,
            ),
        )
    }

    @Test
    fun chatListInfersOutgoingWhenFlagMissing() {
        assertTrue(
            Presence.lastDialogOutgoing(
                flaggedOutgoing = false,
                lastMessageId = 20,
                readInboxMaxId = 10,
                unreadCount = 0,
            ),
        )
        assertFalse(
            Presence.lastDialogOutgoing(
                flaggedOutgoing = false,
                lastMessageId = 20,
                readInboxMaxId = 10,
                unreadCount = 2,
            ),
        )
        assertFalse(
            Presence.lastDialogOutgoing(
                flaggedOutgoing = false,
                lastMessageId = 10,
                readInboxMaxId = 10,
                unreadCount = 0,
            ),
        )
    }

    @Test
    fun chatListMarksSentReadAndPending() {
        val sent = Presence.chatListOutgoingMark(
            outgoing = true,
            isChannel = false,
            lastMessageId = 11,
            readInboxMaxId = 0,
            readOutboxMaxId = 10,
            unreadCount = 0,
        )!!
        assertEquals("\u2713", sent.text)
        assertFalse(sent.read)
        assertFalse(sent.double)
        val read = Presence.chatListOutgoingMark(
            outgoing = false,
            isChannel = false,
            lastMessageId = 10,
            readInboxMaxId = 4,
            readOutboxMaxId = 10,
            unreadCount = 0,
        )!!
        assertEquals("\u2713\u2713", read.text)
        assertTrue(read.read)
        assertTrue(read.double)
        val pending = Presence.chatListOutgoingMark(
            outgoing = true,
            isChannel = false,
            lastMessageId = 0,
            readInboxMaxId = 0,
            readOutboxMaxId = 10,
            unreadCount = 0,
        )!!
        assertEquals("\u2026", pending.text)
        assertTrue(pending.pending)
        assertFalse(pending.read)
    }
}
