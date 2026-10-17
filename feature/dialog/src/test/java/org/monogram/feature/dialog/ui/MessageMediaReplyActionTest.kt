package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.ui.media.MediaAlbumState
import org.monogram.core.ui.media.MediaViewerActions
import org.monogram.core.ui.media.MediaViewerItem
import org.monogram.core.ui.media.MediaViewerKind
import org.monogram.feature.dialog.DialogStore

class MessageMediaReplyActionTest {
    @Test
    fun optionalReplyDefaultsToUnavailable() {
        assertNull(MediaViewerActions().onReply)
        assertNull(messageMediaReplyAction(emptyList(), {}, null))
    }

    @Test
    fun repliesToCurrentExactIdentityNotSelectionAndDismissesFirst() {
        val first = message(chatId = 1, id = 7)
        val current = message(chatId = 1, id = 8)
        val sameIdInOtherChat = message(chatId = 2, id = 8)
        val album = listOf(first, sameIdInOtherChat, current)
        val viewer = MediaAlbumState(album.map(::item), initialIndex = 2)
        viewer.toggleSelection(item(first).id)
        val events = mutableListOf<String>()
        var replied: Message? = null
        val action = messageMediaReplyAction(
            album = album,
            onDismiss = { events += "dismiss" },
            onReply = {
                events += "reply"
                replied = it
            },
        )!!

        action(viewer.current!!)

        assertSame(current, replied)
        assertEquals(listOf("dismiss", "reply"), events)
        assertEquals(listOf(item(first)), viewer.selectedItems())
    }

    @Test
    fun missingPendingAndUnsentItemsDoNotDismissOrReply() {
        val pending = message(id = 7, pending = true)
        val unsent = message(id = -1)
        val events = mutableListOf<String>()
        val action = messageMediaReplyAction(
            album = listOf(pending, unsent),
            onDismiss = { events += "dismiss" },
            onReply = { events += "reply" },
        )!!

        listOf(message(id = 9), pending, unsent).forEach { action(item(it)) }

        assertTrue(events.isEmpty())
    }

    @Test
    fun replyPermissionUsesSendRightsNotForwardProtection() {
        val protected = message().copy(noforwards = true)
        val state = DialogStore.State(
            chatId = PeerId(1),
            canSendPlain = false,
            canSendPhotos = false,
            canForward = false,
        )

        assertTrue(!messageMenuActions(state, protected).canReply)
        assertTrue(messageMenuActions(state.copy(canSendPlain = true), protected).canReply)
        assertTrue(messageMenuActions(state.copy(canSendPhotos = true), protected).canReply)
        assertTrue(!messageMenuActions(state.copy(canSendPlain = true), protected.copy(pending = true)).canReply)
        assertTrue(!messageMenuActions(state.copy(canSendPhotos = true), message(id = 0)).canReply)
    }

    private fun message(chatId: Long = 1, id: Int = 7, pending: Boolean = false) = Message(
        id = MessageId(PeerId(chatId), id),
        senderId = null,
        text = null,
        date = 0,
        outgoing = false,
        mediaKind = "photo",
        pending = pending,
    )

    private fun item(message: Message) = MediaViewerItem(
        id = "${message.id.chatId.value}:${message.id.id}",
        kind = MediaViewerKind.PHOTO,
    )
}
