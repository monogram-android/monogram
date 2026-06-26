package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.presentation.features.chats.conversation.logic.ChannelSponsoredRequestContext
import org.monogram.presentation.features.chats.conversation.logic.shouldRequestChannelSponsoredMessage

class ChannelSponsoredPolicyTest {
    @Test
    fun `requests sponsored message only for main channel feed by default`() {
        assertTrue(
            shouldRequestChannelSponsoredMessage(
                ChannelSponsoredRequestContext(
                    isChannel = true,
                    isGroup = false,
                    isBot = false,
                    currentTopicId = null,
                    rootMessageId = null,
                    viewAsTopics = false,
                    isPremium = false,
                    showSponsoredMessagesForPremium = false
                )
            )
        )
    }

    @Test
    fun `blocks sponsored message for premium users unless opt-in is enabled`() {
        assertFalse(
            shouldRequestChannelSponsoredMessage(
                baseContext(isPremium = true, showSponsoredMessagesForPremium = false)
            )
        )
        assertTrue(
            shouldRequestChannelSponsoredMessage(
                baseContext(isPremium = true, showSponsoredMessagesForPremium = true)
            )
        )
    }

    @Test
    fun `blocks sponsored message for private group bot comments and topics`() {
        assertFalse(shouldRequestChannelSponsoredMessage(baseContext(isChannel = false)))
        assertFalse(shouldRequestChannelSponsoredMessage(baseContext(isGroup = true)))
        assertFalse(shouldRequestChannelSponsoredMessage(baseContext(isBot = true)))
        assertFalse(shouldRequestChannelSponsoredMessage(baseContext(rootMessageId = 10L)))
        assertFalse(shouldRequestChannelSponsoredMessage(baseContext(currentTopicId = 1L)))
        assertFalse(shouldRequestChannelSponsoredMessage(baseContext(viewAsTopics = true)))
    }

    private fun baseContext(
        isChannel: Boolean = true,
        isGroup: Boolean = false,
        isBot: Boolean = false,
        currentTopicId: Long? = null,
        rootMessageId: Long? = null,
        viewAsTopics: Boolean = false,
        isPremium: Boolean = false,
        showSponsoredMessagesForPremium: Boolean = false
    ): ChannelSponsoredRequestContext {
        return ChannelSponsoredRequestContext(
            isChannel = isChannel,
            isGroup = isGroup,
            isBot = isBot,
            currentTopicId = currentTopicId,
            rootMessageId = rootMessageId,
            viewAsTopics = viewAsTopics,
            isPremium = isPremium,
            showSponsoredMessagesForPremium = showSponsoredMessagesForPremium
        )
    }
}
