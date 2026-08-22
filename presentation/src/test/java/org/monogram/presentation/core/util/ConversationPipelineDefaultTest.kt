package org.monogram.presentation.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.monogram.domain.repository.ConversationPipelineMode

class ConversationPipelineDefaultTest {
    @Test
    fun `legacy kill switch overrides and restores rollout default`() {
        assertEquals(
            ConversationPipelineMode.Legacy,
            conversationPipelineModeWithLegacyKillSwitch(
                forceLegacy = true,
                defaultMode = ConversationPipelineMode.New,
            ),
        )
        assertEquals(
            ConversationPipelineMode.New,
            conversationPipelineModeWithLegacyKillSwitch(
                forceLegacy = false,
                defaultMode = ConversationPipelineMode.New,
            ),
        )
    }

    @Test
    fun `stored pipeline mode accepts legacy ordinal and stable name`() {
        assertEquals(
            ConversationPipelineMode.Legacy,
            storedConversationPipelineMode(
                ConversationPipelineMode.Legacy.ordinal,
                ConversationPipelineMode.New,
            ),
        )
        assertEquals(
            ConversationPipelineMode.New,
            storedConversationPipelineMode(
                ConversationPipelineMode.New.name,
                ConversationPipelineMode.Legacy,
            ),
        )
        assertEquals(
            ConversationPipelineMode.Shadow,
            storedConversationPipelineMode("unknown", ConversationPipelineMode.Shadow),
        )
    }

    @Test
    fun `backend-specific pipeline kill switch is unavailable`() {
        assertFalse(isConversationPipelineKillSwitchAvailable())
    }

    @Test
    fun `debug defaults to shadow and release defaults to legacy`() {
        assertEquals(ConversationPipelineMode.Shadow, defaultConversationPipelineMode(isDebug = true))
        assertEquals(ConversationPipelineMode.Legacy, defaultConversationPipelineMode(isDebug = false))
    }
}
