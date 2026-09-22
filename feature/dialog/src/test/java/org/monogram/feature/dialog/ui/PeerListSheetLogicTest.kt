package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.MessageReaction
import org.monogram.core.models.MessageViewer
import org.monogram.core.models.PEER_LIST_FILTER_ALL
import org.monogram.core.models.PeerId
import org.monogram.core.models.Poll
import org.monogram.core.models.PollAnswer
import org.monogram.core.models.filterPeerList
import org.monogram.core.models.peerListFilterChips
import org.monogram.core.models.pollAnswersLabel
import org.monogram.core.models.pollOptionHex

class PeerListSheetLogicTest {
    @Test
    fun longPressingAReactionOpensThatChipAndHidesOthers() {
        val thumbs = MessageReaction(emoticon = "👍", count = 2)
        val users = listOf(
            MessageViewer(PeerId(1), 10, title = "Ada", emoticon = "👍"),
            MessageViewer(PeerId(2), 11, title = "Bob", emoticon = "😂"),
            MessageViewer(PeerId(3), 12, title = "Cid", documentId = 9L),
        )
        val initial = peerListInitialFilter("reactions", thumbs)
        val chips = peerListFilterChips(users, kind = "reactions")
        assertEquals("e:👍", initial)
        assertTrue(chips.any { it.key == initial && it.count == 1 })
        assertEquals(listOf("Ada"), filterPeerList(users, initial).map { it.title })
    }

    @Test
    fun pollSheetStartsOnAllAndFiltersByAnswer() {
        val yes = byteArrayOf(0x01)
        val no = byteArrayOf(0x02)
        val poll = Poll(
            question = "ready?",
            answers = listOf(
                PollAnswer(text = "yes", option = yes),
                PollAnswer(text = "no", option = no),
            ),
            publicVoters = true,
        )
        val users = listOf(
            MessageViewer(PeerId(1), 10, title = "Ada", pollOptionHex = listOf(pollOptionHex(yes))),
            MessageViewer(PeerId(2), 11, title = "Bob", pollOptionHex = listOf(pollOptionHex(no))),
        )
        assertEquals(PEER_LIST_FILTER_ALL, peerListInitialFilter("poll"))
        val chips = peerListFilterChips(users, kind = "poll", poll = poll)
        assertEquals(listOf("all", "p:01", "p:02"), chips.map { it.key })
        assertEquals("yes", pollAnswersLabel(users[0], poll))
        assertEquals(listOf("Bob"), filterPeerList(users, chips[2].key).map { it.title })
    }
}
