package org.monogram.network.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.core.models.pollOptionHex
import org.monogram.network.bridge.message.toViewer
import uniffi.monogram_mtproto.PollVoterDto
import uniffi.monogram_mtproto.ReactionPeerDto

class PeerListMappingTest {
    @Test
    fun reactionPeerKeepsEmoticonAndCustomEmoji() {
        val emoji = ReactionPeerDto(
            peerId = 11L,
            title = "Ada",
            date = 44,
            emoticon = "🔥",
            documentId = 0L,
        ).toViewer()
        assertEquals("Ada", emoji.title)
        assertEquals("🔥", emoji.emoticon)
        assertNull(emoji.documentId)

        val custom = ReactionPeerDto(
            peerId = 12L,
            title = "Bob",
            date = 45,
            emoticon = "",
            documentId = 99L,
        ).toViewer()
        assertEquals(99L, custom.documentId)
        assertNull(custom.emoticon)
    }

    @Test
    fun pollVoterKeepsOptionBytesAsHex() {
        val viewer = PollVoterDto(
            peerId = 7L,
            title = "Ada",
            date = 9,
            options = listOf(byteArrayOf(0x01, 0xa0.toByte()), byteArrayOf(0x62)),
        ).toViewer()
        assertEquals(listOf("01a0", "62"), viewer.pollOptionHex)
        assertEquals("01a0", pollOptionHex(byteArrayOf(0x01, 0xa0.toByte())))
    }
}
