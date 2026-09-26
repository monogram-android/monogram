package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerChoiceListsTest {
    @Test
    fun reactionChipsGroupByEmojiAndCustom() {
        val users = listOf(
            MessageViewer(PeerId(1), 10, title = "A", emoticon = "👍"),
            MessageViewer(PeerId(2), 11, title = "B", emoticon = "👍"),
            MessageViewer(PeerId(3), 12, title = "C", documentId = 9L),
        )
        val chips = peerListFilterChips(users, kind = "reactions")
        assertEquals(PEER_LIST_FILTER_ALL, chips[0].key)
        assertEquals(3, chips[0].count)
        assertEquals("e:👍", chips[1].key)
        assertEquals(2, chips[1].count)
        assertEquals("d:9", chips[2].key)
        assertEquals(1, chips[2].count)
        assertEquals(2, filterPeerList(users, "e:👍").size)
        assertEquals(3L, filterPeerList(users, "d:9").single().peerId.value)
    }

    @Test
    fun pollChipsUseAnswerTextAndMultiVotes() {
        val yes = byteArrayOf(0x01, 0xa0.toByte())
        val no = byteArrayOf(0x62)
        val poll = Poll(
            question = "ready?",
            answers = listOf(
                PollAnswer(text = "yes", option = yes),
                PollAnswer(text = "no", option = no),
            ),
            publicVoters = true,
        )
        val users = listOf(
            MessageViewer(PeerId(1), 10, title = "A", pollOptionHex = listOf(pollOptionHex(yes))),
            MessageViewer(
                PeerId(2),
                11,
                title = "B",
                pollOptionHex = listOf(pollOptionHex(yes), pollOptionHex(no)),
            ),
        )
        val chips = peerListFilterChips(users, kind = "poll", poll = poll)
        assertEquals(3, chips.size)
        assertEquals("yes", chips[1].label)
        assertEquals(2, chips[1].count)
        assertEquals("no", chips[2].label)
        assertEquals(1, chips[2].count)
        assertEquals(
            listOf(1L, 2L),
            filterPeerList(users, chips[1].key).map { it.peerId.value },
        )
        assertEquals("yes, no", pollAnswersLabel(users[1], poll))
    }

    @Test
    fun hexMatchesNativePollJson() {
        assertEquals("01a0", pollOptionHex(byteArrayOf(0x01, 0xa0.toByte())))
        assertTrue(pollOptionHex(ByteArray(0)).isEmpty())
    }
}
