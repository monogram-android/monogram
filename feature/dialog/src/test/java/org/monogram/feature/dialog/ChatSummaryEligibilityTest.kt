package org.monogram.feature.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class ChatSummaryEligibilityTest {
    @Test
    fun eligibleMessageIsNotSummarizableInBotChat() {
        val chatId = PeerId(7)
        val message = Message(
            MessageId(chatId, 11),
            chatId,
            "Long message. ".repeat(12),
            100,
            outgoing = false
        )
        val dialog = DialogStore.State(chatId)
        assertTrue(dialog.canSummarizeMessage(message))
        assertFalse(dialog.copy(isBot = true).canSummarizeMessage(message))
        assertFalse(
            dialog.copy(textTools = DialogTextToolsState(providerConfigured = false))
                .canSummarizeMessage(message)
        )
    }
}
