package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerModelTest {
    @Test
    fun fallsBackToPeerSlotWhenNoStoredAvatar() {
        val id = PeerId(42)
        assertEquals("avatar:42", peerAvatarCacheKey(id, null))
        assertEquals("avatar:42", peerAvatarCacheKey(id, "  "))
        assertEquals("photo:99", peerAvatarCacheKey(id, "photo:99"))
    }

    @Test
    fun detectsPlaceholderPeerTitles() {
        assertTrue(isPlaceholderPeerTitle("", 100))
        assertTrue(isPlaceholderPeerTitle("Chat", 100))
        assertTrue(isPlaceholderPeerTitle("User", 100))
        assertTrue(isPlaceholderPeerTitle("Channel 100", 100))
        assertFalse(isPlaceholderPeerTitle("Alice", 100))
    }

    @Test
    fun preferredPeerTitlePrefersSavedRealTitleOverPlaceholder() {
        assertEquals("Alice", preferredPeerTitle("User 100", "Alice", 100))
        assertEquals("New Name", preferredPeerTitle("New Name", "Alice", 100))
    }
}
