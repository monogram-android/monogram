package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationConversationTest {
    @Test
    fun peerKeyIsStablePerChat() {
        assertEquals("peer:42", NotificationConversation.peerPersonKey(42L))
        assertEquals("peer:-7", NotificationConversation.peerPersonKey(-7L))
    }

    @Test
    fun selfKeyIsOutgoingEvenWhenUserKeyIsMissing() {
        assertTrue(NotificationConversation.isOutgoing(NotificationConversation.SELF_PERSON_KEY, null))
        assertTrue(
            NotificationConversation.isOutgoing(
                NotificationConversation.SELF_PERSON_KEY,
                NotificationConversation.SELF_PERSON_KEY,
            ),
        )
    }

    @Test
    fun incomingPeerMessagesStayIncoming() {
        val peer = NotificationConversation.peerPersonKey(7L)
        assertFalse(NotificationConversation.isOutgoing(peer, NotificationConversation.SELF_PERSON_KEY))
        assertFalse(NotificationConversation.isOutgoing(peer, peer))
    }

    @Test
    fun nullPersonIsOutgoingOnlyForTheSelfUser() {
        assertTrue(NotificationConversation.isOutgoing(null, NotificationConversation.SELF_PERSON_KEY))
        assertTrue(NotificationConversation.isOutgoing("", NotificationConversation.SELF_PERSON_KEY))
        assertFalse(NotificationConversation.isOutgoing(null, NotificationConversation.peerPersonKey(7L)))
        assertFalse(NotificationConversation.isOutgoing(null, null))
    }
}
