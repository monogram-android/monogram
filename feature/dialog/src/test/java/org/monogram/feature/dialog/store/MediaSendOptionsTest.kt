package org.monogram.feature.dialog.store

import org.junit.Assert.*
import org.junit.Test
import org.monogram.core.models.PeerId
import org.monogram.core.models.UploadItem
import org.monogram.feature.dialog.DialogStore

class MediaSendOptionsTest {
    private val photo = UploadItem("synthetic-photo", "photo")
    private val state = DialogStore.State(PeerId(7), pendingAttach = listOf(photo))

    @Test
    fun attachmentOnlyAndPhotoOnlyPermissionsAreSupported() {
        assertTrue(mediaOptionsAllowed(state.copy(canSendPlain = false)))
        assertTrue(
            mediaOptionsAllowed(
                state.copy(
                    pendingAttach = listOf(
                        UploadItem(
                            "synthetic-file",
                            "document"
                        )
                    ), canSendPhotos = false
                )
            )
        )
        assertFalse(mediaOptionsAllowed(state.copy(canSendPhotos = false)))
    }

    @Test
    fun invalidAlbumsAndRestrictedComposerStatesAreRejected() {
        assertFalse(
            mediaOptionsAllowed(
                state.copy(
                    pendingAttach = listOf(
                        photo,
                        UploadItem("synthetic-file", "document")
                    )
                )
            )
        )
        assertFalse(mediaOptionsAllowed(state.copy(pendingAttach = List(11) { photo })))
        assertFalse(mediaOptionsAllowed(state.copy(pendingAttach = emptyList())))
        assertFalse(mediaOptionsAllowed(state.copy(sending = true)))
        assertFalse(mediaOptionsAllowed(state.copy(draftUnsupported = true)))
        assertFalse(mediaOptionsAllowed(state.copy(left = true)))
        assertFalse(mediaOptionsAllowed(state.copy(blockedByMe = true)))
        assertFalse(mediaOptionsAllowed(state.copy(canView = false)))
    }

    @Test
    fun newerAttachmentDraftReplyAndFormattingAreNeverConsumed() {
        assertTrue(
            mediaSelectionUnchanged(
                state,
                state.copy(draft = "new text", composerRevision = 1)
            )
        )
        assertFalse(mediaSelectionUnchanged(state, state.copy(attachmentRevision = 1)))
        assertTrue(mediaComposerUnchanged(state, state.copy(sending = true)))
        assertFalse(mediaComposerUnchanged(state, state.copy(composerRevision = 1)))
        assertFalse(mediaComposerUnchanged(state, state.copy(pendingAttach = emptyList())))
        assertFalse(mediaComposerUnchanged(state, state.copy(draft = "new text")))
        assertFalse(mediaComposerUnchanged(state, state.copy(draftReplyId = 42)))
        assertFalse(mediaComposerUnchanged(state, state.copy(draftFormatting = true)))
        assertFalse(mediaComposerUnchanged(state, state.copy(linkPreviewHidden = true)))
    }
}
