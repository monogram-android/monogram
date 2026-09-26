package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchTest {
    @Test
    fun contactsSearchSplitsPeopleAndChats() {
        val page = ContactsSearch(
            people = listOf(
                SearchPeer(id = PeerId(7), title = "Ada", username = "ada", kind = "user"),
            ),
            chats = listOf(
                SearchPeer(
                    id = PeerId(-1_000_000_000_001),
                    title = "News",
                    username = "news",
                    kind = "channel",
                    isChannel = true,
                ),
            ),
        )
        assertEquals("user", page.people.single().kind)
        assertTrue(page.chats.single().isChannel)
        assertFalse(page.people.single().isBot)
    }

    @Test
    fun globalMessageSearchKeepsOffsetTriple() {
        val page = GlobalMessageSearch(
            messages = emptyList(),
            nextRate = 44,
            nextPeerId = PeerId(-5),
            nextOffsetId = 12,
        )
        assertEquals(44, page.nextRate)
        assertEquals(-5L, page.nextPeerId.value)
        assertEquals(12, page.nextOffsetId)
    }
}
