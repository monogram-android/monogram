package org.monogram.core.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

class ChatMembershipStateTest {
    @Test
    fun leftDialogSurvivesTheRoundTrip() {
        val comments = Chat(id = PeerId(500), title = "Channel comments", isGroup = true, left = true)

        val restored = comments.toEntity().toModel()

        assertTrue(restored.left)
        assertTrue(restored.isGroup)
    }

    @Test
    fun joinedDialogStaysVisibleAfterRestore() {
        val joined = Chat(id = PeerId(10), title = "Team", isGroup = true)

        val restored = joined.toEntity().toModel()

        assertFalse(restored.left)
    }
}
