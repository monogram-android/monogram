package org.monogram.presentation.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.repository.ConversationPipelineMode

class ConversationPipelineDefaultTest {
    @Test
    fun `legacy kill switch overrides and restores rollout default`() {
        assertEquals(
            ConversationPipelineMode.Legacy,
            conversationPipelineModeWithLegacyKillSwitch(
                forceLegacy = true,
                defaultMode = ConversationPipelineMode.New
            )
        )
        assertEquals(
            ConversationPipelineMode.New,
            conversationPipelineModeWithLegacyKillSwitch(
                forceLegacy = false,
                defaultMode = ConversationPipelineMode.New
            )
        )
    }

    @Test
    fun `stored pipeline mode accepts legacy ordinal and stable name`() {
        assertEquals(
            ConversationPipelineMode.Legacy,
            storedConversationPipelineMode(
                ConversationPipelineMode.Legacy.ordinal,
                ConversationPipelineMode.New
            )
        )
        assertEquals(
            ConversationPipelineMode.New,
            storedConversationPipelineMode(
                ConversationPipelineMode.New.name,
                ConversationPipelineMode.Legacy
            )
        )
        assertEquals(
            ConversationPipelineMode.Shadow,
            storedConversationPipelineMode("unknown", ConversationPipelineMode.Shadow)
        )
    }

    @Test
    fun `legacy kill switch is available only in official libre`() {
        assertEquals(true, isConversationPipelineKillSwitchAvailable(true, true))
        assertEquals(false, isConversationPipelineKillSwitchAvailable(true, false))
        assertEquals(false, isConversationPipelineKillSwitchAvailable(false, true))
        assertEquals(false, isConversationPipelineKillSwitchAvailable(false, false))
    }

    @Test
    fun `official libre defaults to new in every build type`() {
        assertEquals(
            ConversationPipelineMode.New,
            defaultConversationPipelineMode(
                isOfficialTdlib = true,
                isLibreRuntime = true,
                isDebug = false
            )
        )
        assertEquals(
            ConversationPipelineMode.New,
            defaultConversationPipelineMode(
                isOfficialTdlib = true,
                isLibreRuntime = true,
                isDebug = true
            )
        )
    }

    @Test
    fun `other debug variants retain shadow default`() {
        assertEquals(
            ConversationPipelineMode.Shadow,
            defaultConversationPipelineMode(
                isOfficialTdlib = true,
                isLibreRuntime = false,
                isDebug = true
            )
        )
        assertEquals(
            ConversationPipelineMode.Shadow,
            defaultConversationPipelineMode(
                isOfficialTdlib = false,
                isLibreRuntime = true,
                isDebug = true
            )
        )
    }

    @Test
    fun `other release variants retain legacy default`() {
        assertEquals(
            ConversationPipelineMode.Legacy,
            defaultConversationPipelineMode(
                isOfficialTdlib = true,
                isLibreRuntime = false,
                isDebug = false
            )
        )
        assertEquals(
            ConversationPipelineMode.Legacy,
            defaultConversationPipelineMode(
                isOfficialTdlib = false,
                isLibreRuntime = true,
                isDebug = false
            )
        )
    }
}
