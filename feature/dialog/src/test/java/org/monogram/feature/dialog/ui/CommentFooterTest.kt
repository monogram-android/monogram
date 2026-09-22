package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentFooterTest {
    @Test
    fun mediaStillCoversBottomWhenThereAreNoComments() {
        assertEquals(
            true,
            shouldBleedMediaBottom(
                edgeMedia = true,
                mediaCaption = false,
                hasComments = false,
            ),
        )
    }

    @Test
    fun commentsKeepMediaFromCoveringTheFooter() {
        assertEquals(
            false,
            shouldBleedMediaBottom(
                edgeMedia = true,
                mediaCaption = false,
                hasComments = true,
            ),
        )
    }
}
