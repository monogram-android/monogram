package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.models.ARCHIVE_FOLDER_ID
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.SearchPeer

class ChatsGlobalSearchMappingTest {
    @Test
    fun archiveUsesWireFolderOne() {
        assertEquals(MAIN_FOLDER_WIRE_ID, searchFolderId(null))
        assertEquals(ARCHIVE_FOLDER_WIRE_ID, searchFolderId(ARCHIVE_FOLDER_ID))
        assertEquals(MAIN_FOLDER_WIRE_ID, searchFolderId(7))
    }

    @Test
    fun emptySearchQueryIsRecognized() {
        assertTrue(isEmptySearchQuery(TelegramError.parse("SEARCH_QUERY_EMPTY")))
        assertFalse(isEmptySearchQuery(TelegramError.parse("offline")))
    }

    @Test
    fun hasMoreRequiresFullPageAndOffsets() {
        assertTrue(globalSearchHasMore(GLOBAL_SEARCH_LIMIT, 1, -1, 9))
        assertFalse(globalSearchHasMore(3, 1, -1, 9))
        assertFalse(globalSearchHasMore(GLOBAL_SEARCH_LIMIT, 0, 0, 0))
    }

    @Test
    fun loadedChatNotMatchingTitleStaysInPeople() {
        val loaded = Chat(PeerId(2), "Ada Lovelace")
        val ids = localSearchMatchIds(listOf(loaded), "bob")
        val people = excludeKnownPeers(
            listOf(SearchPeer(PeerId(2), "Bob", username = "bob", kind = "user")),
            ids,
        )
        assertTrue(ids.isEmpty())
        assertEquals("Bob", people.single().title)
    }

    @Test
    fun peerMapsUsernameIntoPreview() {
        val chat = SearchPeer(PeerId(2), "Bob", username = "bob", kind = "user").toChat()
        assertEquals("@bob", chat.lastMessagePreview)
        assertEquals("Bob", chat.title)
    }

    @Test
    fun messageTitlePrefersListedChat() {
        val message = Message(
            id = MessageId(PeerId(-9), 4),
            senderId = null,
            text = "hi",
            date = 1,
            outgoing = false,
            senderName = "Sender",
        )
        assertEquals(
            "News",
            titleForSearchMessage(
                message,
                chats = listOf(Chat(PeerId(-9), "News")),
                people = emptyList(),
                foundChats = emptyList(),
            ),
        )
    }

    @Test
    fun searchMessageJumpUsesNotificationPath() {
        val message = Message(
            id = MessageId(PeerId(-42), 88),
            senderId = null,
            text = "hit",
            date = 1,
            outgoing = false,
        )
        assertEquals(PeerId(-42) to 88, searchMessageJump(message))
    }
}
