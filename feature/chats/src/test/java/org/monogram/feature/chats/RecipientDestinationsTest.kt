package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId
import org.monogram.core.models.canSelectAsForwardRecipient

class RecipientDestinationsTest {
    @Test
    fun savedMessagesStaySelectableWhenSendFlagsAreStale() {
        val saved = chat(id = 9, canSendPlain = false, canSendPhotos = false)
        val rootPicker = { chat: Chat ->
            chat.canSelectAsForwardRecipient(requiresPhotos = true, selfPeerId = PeerId(9))
        }
        assertTrue(rootPicker(saved))
        assertEquals(
            listOf(9L),
            recipientPaneIds(
                paneIds = listOf(9),
                chat = { saved },
                selectingRecipient = true,
                canSelectRecipient = rootPicker,
            ),
        )
    }

    @Test
    fun channelsWithoutPostAndLeftChatsAreHidden() {
        val channel = chat(id = 2, isChannel = true, canSendPlain = false)
        val left = chat(id = 3, left = true)
        val ok = chat(id = 4)
        val saved = chat(id = 9, canSendPlain = false)
        val rootPicker = { chat: Chat ->
            chat.canSelectAsForwardRecipient(requiresPhotos = false, selfPeerId = PeerId(9))
        }
        val ids = recipientPaneIds(
            paneIds = listOf(2, 3, 4, 9),
            chat = { id ->
                when (id) {
                    2L -> channel
                    3L -> left
                    4L -> ok
                    9L -> saved
                    else -> null
                }
            },
            selectingRecipient = true,
            canSelectRecipient = rootPicker,
        )
        assertEquals(listOf(4L, 9L), ids)
    }

    @Test
    fun photoForwardsDropChatsThatCannotSendPhotos() {
        val photosBlocked = chat(id = 5, canSendPhotos = false)
        assertFalse(photosBlocked.canSelectAsForwardRecipient(requiresPhotos = true, selfPeerId = null))
        assertTrue(photosBlocked.canSelectAsForwardRecipient(requiresPhotos = false, selfPeerId = null))
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
