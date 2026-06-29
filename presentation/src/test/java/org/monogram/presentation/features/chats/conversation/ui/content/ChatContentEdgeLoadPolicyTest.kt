package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContentEdgeLoadPolicyTest {
    @Test
    fun `same older anchor does not trigger duplicate load`() {
        val decision = decideEdgeLoad(
            isComments = false,
            isOldestLoaded = false,
            isLatestLoaded = false,
            isAtBottom = false,
            nearOlderEdge = true,
            nearNewerEdge = false,
            olderAnchorId = 100L,
            newerAnchorId = 500L,
            lastOlderAnchorId = 100L,
            lastNewerAnchorId = null
        )

        assertFalse(decision.shouldLoadOlder)
        assertEquals(null, decision.nextOlderAnchorId)
    }

    @Test
    fun `new older anchor triggers load`() {
        val decision = decideEdgeLoad(
            isComments = false,
            isOldestLoaded = false,
            isLatestLoaded = false,
            isAtBottom = false,
            nearOlderEdge = true,
            nearNewerEdge = false,
            olderAnchorId = 99L,
            newerAnchorId = 500L,
            lastOlderAnchorId = 100L,
            lastNewerAnchorId = null
        )

        assertTrue(decision.shouldLoadOlder)
        assertEquals(99L, decision.nextOlderAnchorId)
    }

    @Test
    fun `root chat newer load is gated while at bottom`() {
        val decision = decideEdgeLoad(
            isComments = false,
            isOldestLoaded = false,
            isLatestLoaded = false,
            isAtBottom = true,
            nearOlderEdge = false,
            nearNewerEdge = true,
            olderAnchorId = 99L,
            newerAnchorId = 500L,
            lastOlderAnchorId = null,
            lastNewerAnchorId = null
        )

        assertFalse(decision.shouldLoadNewer)
    }

    @Test
    fun `comments newer load stays enabled away from latest`() {
        val decision = decideEdgeLoad(
            isComments = true,
            isOldestLoaded = false,
            isLatestLoaded = false,
            isAtBottom = true,
            nearOlderEdge = false,
            nearNewerEdge = true,
            olderAnchorId = 99L,
            newerAnchorId = 500L,
            lastOlderAnchorId = null,
            lastNewerAnchorId = 499L
        )

        assertTrue(decision.shouldLoadNewer)
        assertEquals(500L, decision.nextNewerAnchorId)
    }
}
