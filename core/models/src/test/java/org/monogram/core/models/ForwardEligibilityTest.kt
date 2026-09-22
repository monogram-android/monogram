package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardEligibilityTest {
    @Test
    fun chatWideProtectionBlocksEveryShape() {
        val photo = message(mediaKind = "photo")
        assertTrue(photo.canForwardFrom(chatCanForward = true))
        assertFalse(photo.canForwardFrom(chatCanForward = false))
    }

    @Test
    fun perMessageNoforwardsBlocksEvenWhenChatAllows() {
        assertFalse(message(noforwards = true).canForwardFrom(true))
    }

    @Test
    fun pendingAndServiceAreNotSources() {
        assertFalse(message(pending = true).canForwardFrom(true))
        assertFalse(message(mediaKind = "service").canForwardFrom(true))
        assertFalse(message(id = 0).canForwardFrom(true))
    }

    @Test
    fun destinationRejectsLeftKickedAndUnpostableChannels() {
        val left = chat(left = true)
        val channel = chat(isChannel = true, canSendPlain = false)
        val group = chat(isGroup = true, canSendPlain = false)
        assertFalse(left.canReceiveForward(requiresPhotos = false))
        assertFalse(channel.canReceiveForward(requiresPhotos = false))
        assertFalse(group.canReceiveForward(requiresPhotos = false))
    }

    @Test
    fun photoForwardsNeedPhotoRightExceptSavedMessages() {
        val photosBlocked = chat(canSendPhotos = false)
        val saved = chat(id = 9, canSendPhotos = false, canSendPlain = false)
        assertFalse(photosBlocked.canReceiveForward(requiresPhotos = true))
        assertTrue(photosBlocked.canReceiveForward(requiresPhotos = false))
        assertTrue(saved.canReceiveForward(requiresPhotos = true, savedMessages = true))
        assertFalse(chat(canView = false).canReceiveForward(requiresPhotos = false, savedMessages = true))
        assertTrue(
            saved.canSelectAsForwardRecipient(requiresPhotos = true, selfPeerId = PeerId(9)),
        )
        assertFalse(
            saved.canSelectAsForwardRecipient(requiresPhotos = true, selfPeerId = PeerId(1)),
        )
    }

    @Test
    fun photoAndVideoNeedThePhotoSendRight() {
        assertTrue(message(mediaKind = "photo").requiresForwardPhotoRight())
        assertTrue(message(mediaKind = "video").requiresForwardPhotoRight())
        assertTrue(message(mediaKind = "gif").requiresForwardPhotoRight())
        assertFalse(message(mediaKind = null).requiresForwardPhotoRight())
        assertFalse(message(mediaKind = "document").requiresForwardPhotoRight())
    }

    @Test
    fun filesystemPathsAreNeverUserFacingShareText() {
        assertTrue(looksLikeFilesystemPath("""C:\Users\GDLBO\Downloads\photo_2026-09-23_00-38-15.jpg"""))
        assertTrue(looksLikeFilesystemPath("/data/user/0/org.monogram/cache/incoming-shares/1-photo.jpg"))
        assertTrue(looksLikeFilesystemPath("content://media/external/images/1"))
        assertTrue(looksLikeFilesystemPath("file:///storage/emulated/0/Download/a.jpg"))
        assertFalse(looksLikeFilesystemPath("https://example.com/photo.jpg"))
        assertFalse(looksLikeFilesystemPath("please send the photo"))
        assertEquals("", userFacingShareText("""C:\Users\GDLBO\Downloads\photo.jpg"""))
        assertEquals("hi", userFacingShareText("  hi  "))
    }

    private fun message(
        id: Int = 7,
        pending: Boolean = false,
        mediaKind: String? = null,
        noforwards: Boolean = false,
    ) = Message(
        id = MessageId(PeerId(1), id),
        senderId = PeerId(1),
        text = null,
        date = 0,
        outgoing = false,
        pending = pending,
        mediaKind = mediaKind,
        noforwards = noforwards,
    )

    private fun chat(
        id: Long = 1,
        canView: Boolean = true,
        left: Boolean = false,
        isChannel: Boolean = false,
        isGroup: Boolean = false,
        canSendPlain: Boolean = true,
        canSendPhotos: Boolean = true,
    ) = Chat(
        id = PeerId(id),
        title = "Chat",
        isChannel = isChannel,
        isGroup = isGroup,
        left = left,
        canView = canView,
        canSendPlain = canSendPlain,
        canSendPhotos = canSendPhotos,
    )
}
