package org.monogram.core.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewerModelTest {
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
    fun forwardFlagIsExplicitNotHardcodedTrue() {
        assertTrue(MediaViewerActions().canForward)
        assertFalse(MediaViewerActions(canForward = false).canForward)
    }

    @Test
    fun photosNeverLoopAndAreNotVideo() {
        assertFalse(photo().loops)
        assertFalse(photo().isVideo)
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
    fun countersAndKindSuffixComeFromAlbumPosition() {
        val album = MediaAlbumState(listOf(photo("a"), video(12, "b"), photo("c"), video(20, "d")), 2)
        assertEquals(3, album.index + 1)
        assertEquals(4, album.count)
        assertTrue(album.current?.isVideo == false)
        album.index = 3
        assertTrue(album.current?.isVideo == true)
    }
}
