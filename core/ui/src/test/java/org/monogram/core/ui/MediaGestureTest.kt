package org.monogram.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.ui.components.boundedMediaOffset
import org.monogram.core.ui.components.focalMediaOffset
import org.monogram.core.ui.components.mediaTime
import org.monogram.core.ui.components.shouldDismissMedia

class MediaGestureTest {
    @Test fun landscapePhotoCannotPanVerticallyIntoLetterbox() {
        assertEquals(Offset(200f, 0f), boundedMediaOffset(Offset(900f, 900f), 2f, Size(400f, 800f), Size(1600f, 900f)))
    }

    @Test fun portraitPhotoPanStopsAtVisibleEdge() {
        assertEquals(Offset(-200f, 400f), boundedMediaOffset(Offset(-900f, 900f), 2f, Size(400f, 800f), Size(400f, 800f)))
        assertEquals(Offset.Zero, boundedMediaOffset(Offset(900f, 900f), 1f, Size(400f, 800f), Size(400f, 800f)))
    }

    @Test fun pinchKeepsFocalPixelStationary() {
        val result = focalMediaOffset(Offset.Zero, Offset(300f, 500f), Offset(200f, 400f), 1f, 2f, Offset.Zero)
        assertEquals(Offset(-100f, -100f), result)
    }

    @Test fun shortDismissReturnsToMediaButIntentionalSwipeCloses() {
        assertFalse(shouldDismissMedia(120f, 1000f))
        assertTrue(shouldDismissMedia(181f, 1000f))
        assertTrue(shouldDismissMedia(-181f, 1000f))
        assertFalse(shouldDismissMedia(200f, 0f))
    }

    @Test fun playbackClockHandlesLongVideosAndUnknownDuration() {
        assertEquals("0:00", mediaTime(-1L))
        assertEquals("1:05", mediaTime(65_000L))
        assertEquals("1:01:05", mediaTime(3_665_000L))
    }
}
