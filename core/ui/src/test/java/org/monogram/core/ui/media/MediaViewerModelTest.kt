package org.monogram.core.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewerModelTest {
    @Test
    fun viewerDismissRequiresDownwardProgressBeyondThreshold() {
        assertFalse(shouldDismissViewer(250f, 1000f))
        assertFalse(shouldDismissViewer(100f, 1000f))
        assertFalse(shouldDismissViewer(-700f, 1000f))
        assertFalse(shouldDismissViewer(500f, 0f))
        assertTrue(shouldDismissViewer(251f, 1000f))
        assertTrue(shouldDismissViewer(300f, 1000f))
    }

    @Test
    fun fillingAndReorderingAlbumKeepsCurrentIdentityUnlessIndexExplicit() {
        val state = MediaAlbumState(listOf(photo("b")))
        state.update(listOf(photo("a"), photo("b"), photo("c")))
        assertEquals(1, state.index)
        assertEquals("b", state.current?.id)
        state.update(listOf(photo("c"), photo("a"), photo("b")))
        assertEquals(2, state.index)
        assertEquals("b", state.current?.id)
        state.update(state.items, 0)
        assertEquals("c", state.current?.id)
    }

    @Test
    fun albumSaverRestoresIndexAndSelectionAndDropsMissingIds() {
        val items = listOf(photo("a"), photo("b"), photo("c"))
        val original = MediaAlbumState(items, 2).apply {
            toggleSelection("a")
            toggleSelection("c")
        }
        val saver = albumStateSaver(items)
        val saved = with(saver) {
            androidx.compose.runtime.saveable.SaverScope { true }.save(original)!!
        }
        val restored = saver.restore(saved)!!
        assertEquals(2, restored.index)
        assertEquals(listOf("a", "c"), restored.selectedItems().map { it.id })
        val reduced = albumStateSaver(items.take(2)).restore(saved)!!
        assertEquals(1, reduced.index)
        assertEquals(listOf("a"), reduced.selectedItems().map { it.id })
    }

    private fun video(duration: Int?, id: String = "v", protected: Boolean = false) = MediaViewerItem(
        id = id,
        kind = MediaViewerKind.VIDEO,
        durationSeconds = duration,
        protectedContent = protected,
    )

    private fun photo(id: String = "p") = MediaViewerItem(id = id, kind = MediaViewerKind.PHOTO)

    @Test
    fun shortVideosLoopAndLongOnesDoNot() {
        assertTrue("12s clip loops", video(12).loops)
        assertTrue("exactly 30s loops", video(30).loops)
        assertFalse("46s clip must not loop", video(46).loops)
        assertFalse("200s clip must not loop", video(200).loops)
        assertFalse("unknown duration does not loop", video(null).loops)
    }

    @Test
    fun protectedVideosNeverLoop() {
        assertFalse(video(12, protected = true).loops)
    }

    @Test
    fun audioAndVoiceArePlayableMessageMediaWithoutVideoOrLooping() {
        for (kind in listOf(MediaViewerKind.AUDIO, MediaViewerKind.VOICE)) {
            val item = MediaViewerItem(id = kind.name, kind = kind, durationSeconds = 12, forceLoop = true)
            assertTrue(item.isPlayable)
            assertTrue(item.isAudio)
            assertTrue(item.isMessageMedia)
            assertFalse(item.isVideo)
            assertFalse(item.loops)
        }
    }

    @Test
    fun videoNotesNeverLoopEvenWithAnExplicitLoopRequest() {
        val note = video(12).copy(kind = MediaViewerKind.VIDEO_NOTE)
        assertTrue(note.isVideo)
        assertTrue(note.isPlayable)
        assertTrue(note.isMessageMedia)
        assertFalse(note.isAudio)
        assertFalse(note.loops)
        assertFalse(note.copy(forceLoop = true).loops)
    }

    @Test
    fun forwardFlagIsExplicitNotHardcodedTrue() {
        assertTrue(MediaViewerActions().canForward)
        assertFalse(MediaViewerActions(canForward = false).canForward)
    }

    @Test
    fun photosNeverLoopAndAreNotVideo() {
        assertFalse(photo().loops)
        assertFalse(photo().isVideo)
        assertFalse(photo().isAudio)
        assertFalse(photo().isPlayable)
        assertFalse(photo().isMessageMedia)
        assertFalse(photo().copy(forceLoop = true).loops)
    }

    @Test
    fun explicitLoopRequestWins() {
        assertTrue(video(46).copy(forceLoop = true).loops)
    }

    @Test
    fun albumStateClampsIndexAndReportsSize() {
        val album = MediaAlbumState(listOf(photo("a"), video(12, "b")), 5)
        assertEquals(1, album.index)
        assertEquals(2, album.count)
        assertTrue(album.hasAlbum)
        assertEquals("b", album.current?.id)

        album.update(listOf(photo("solo")), index = 3)
        assertEquals(0, album.index)
        assertFalse(album.hasAlbum)
        assertEquals("solo", album.current?.id)
    }

    @Test
    fun refreshingTheAlbumKeepsTheReachedPosition() {
        val album = MediaAlbumState(listOf(photo("a"), video(12, "b"), photo("c")), 0)
        album.index = 2
        album.update(listOf(photo("a"), video(12, "b"), photo("c")), album.index)
        assertEquals("a rebuilt list must not rewind the album", 2, album.index)
        assertEquals("c", album.current?.id)

        // Shrinking the album still clamps into range.
        album.update(listOf(photo("a")), album.index)
        assertEquals(0, album.index)
    }

    @Test
    fun emptySelectionTargetsCurrentAndSubsetKeepsAlbumOrder() {
        val album = MediaAlbumState(listOf(photo("a"), photo("b"), photo("c")), 1)
        assertEquals(listOf("b"), album.selectedItems().map { it.id })
        album.toggleSelection("c")
        album.toggleSelection("a")
        assertTrue(album.selection.active)
        assertEquals(listOf("a", "c"), album.selectedItems().map { it.id })
        album.clearSelection()
        assertEquals(listOf("b"), album.selectedItems().map { it.id })
    }

    @Test
    fun removedSelectionFallsBackToCurrentAndUnknownIdsAreIgnored() {
        val album = MediaAlbumState(listOf(photo("a"), photo("b")))
        album.toggleSelection("missing")
        assertFalse(album.selection.active)
        album.toggleSelection("b")
        album.update(listOf(photo("a")))
        assertFalse(album.selection.active)
        assertEquals(listOf("a"), album.selectedItems().map { it.id })
    }

    @Test
    fun discoveredHdrSurvivesFeatureListRefreshAndCanBeCleared() {
        val album = MediaAlbumState(listOf(photo("a"), photo("b")))
        album.setHdr("a", MediaHdr.GainMap)
        album.update(listOf(photo("a"), photo("b")))
        assertEquals(MediaHdr.GainMap, album.current?.hdr)
        assertEquals(MediaHdr.None, album.items[1].hdr)
        album.setHdr("a", MediaHdr.None)
        assertEquals(MediaHdr.None, album.current?.hdr)
    }

    @Test
    fun hdrRequiresHdrTransferOrDolbyVisionMime() {
        fun format(transfer: Int) = androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.VIDEO_H265)
            .setColorInfo(androidx.media3.common.ColorInfo.Builder().setColorTransfer(transfer).build())
            .build()
        assertEquals(MediaHdr.None, hdrForVideoFormat(null))
        assertEquals(MediaHdr.None, hdrForVideoFormat(format(androidx.media3.common.C.COLOR_TRANSFER_SDR)))
        assertEquals(MediaHdr.Video("HDR10"), hdrForVideoFormat(format(androidx.media3.common.C.COLOR_TRANSFER_ST2084)))
        assertEquals(MediaHdr.Video("HLG"), hdrForVideoFormat(format(androidx.media3.common.C.COLOR_TRANSFER_HLG)))
        assertEquals(MediaHdr.Video("Dolby Vision"), hdrForVideoFormat(
            androidx.media3.common.Format.Builder().setSampleMimeType(androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION).build(),
        ))
    }

    @Test
    fun countersAndKindSuffixComeFromAlbumPosition() {
        val album = MediaAlbumState(listOf(photo("a"), video(12, "b"), photo("c"), video(20, "d")), 2)
        assertEquals(3, album.index + 1)
        assertEquals(4, album.count)
        assertTrue(album.current?.isVideo == false)
        album.index = 3
        assertTrue(album.current?.isVideo == true)
    }
}
