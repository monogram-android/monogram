package org.monogram.core.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

/**
 * Mute state is cache state: the dialog's own setting ([Chat.muteOverride]) and the effective flag
 * ([Chat.muted]) have to survive the Room round-trip, otherwise a restored row cannot tell an
 * explicitly muted dialog from one that inherits the peer type default.
 */
class ChatMuteStateTest {
    @Test
    fun explicitMuteAndUnreadMarkSurviveTheRoundTrip() {
        val chat = Chat(
            id = PeerId(7),
            title = "Ada",
            muted = true,
            muteOverride = true,
            unreadMark = true,
        )

        val restored = chat.toEntity().toModel()

        assertTrue(restored.muted)
        assertTrue(restored.muteOverride)
        assertTrue(restored.unreadMark)
    }

    @Test
    fun inheritedMuteStaysDistinguishableFromAnOwnSetting() {
        val inherited = Chat(id = PeerId(7), title = "Ada", muted = true, muteOverride = false)

        val restored = inherited.toEntity().toModel()

        assertTrue(restored.muted)
        assertFalse(restored.muteOverride)
    }
}
