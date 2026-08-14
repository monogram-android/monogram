package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationUpdateKeyTest {
    @Test
    fun `poll answer uses mapped message key`() {
        val update =
            TdApi.UpdatePollAnswer(77L, TdApi.MessageSenderUser(5L), emptyArray(), intArrayOf())

        assertEquals(10L to 20L, conversationUpdatedMessageKey(update, 10L to 20L))
        assertNull(conversationUpdatedMessageKey(update))
        assertTrue(conversationUpdateRequiresMessageRefresh(update))
    }

    @Test
    fun `message update uses its own key`() {
        val update = TdApi.UpdateMessageEdited(10L, 20L, 30, null)

        assertEquals(10L to 20L, conversationUpdatedMessageKey(update, 1L to 2L))
        assertFalse(conversationUpdateRequiresMessageRefresh(update))
    }
}
