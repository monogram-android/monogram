package org.monogram.feature.dialog.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

class DialogForwardTargetsTest {
    @Test
    fun savedMessagesStayEligibleWhenSendFlagsAreStale() {
        val saved = chat(9, canSendPlain = false, canSendPhotos = false)
        val left = chat(3, left = true)
        val channel = chat(2, isChannel = true, canSendPlain = false)
        val ok = chat(4)
        val targets = eligibleForwardTargets(
            chats = listOf(saved, left, channel, ok),
            requiresPhotos = true,
            selfPeerId = PeerId(9),
        )
        assertEquals(listOf(9L, 4L), targets.map { it.id.value })
        assertTrue(targets.any { it.id.value == 9L })
    }

    @Test
    fun unknownPeerIsNotAnEligibleTarget() {
        val ok = chat(4)
        val targets = eligibleForwardTargets(listOf(ok), requiresPhotos = false, selfPeerId = PeerId(9))
        assertTrue(targets.none { it.id.value == 99L })
        assertEquals(listOf(4L), targets.map { it.id.value })
    }

    private fun chat(
        id: Long,
        isChannel: Boolean = false,
        left: Boolean = false,
        canSendPlain: Boolean = true,
        canSendPhotos: Boolean = true,
    ) = Chat(
        id = PeerId(id),
        title = "Chat $id",
        isChannel = isChannel,
        left = left,
        canSendPlain = canSendPlain,
        canSendPhotos = canSendPhotos,
    )
}
