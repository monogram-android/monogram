package org.monogram.feature.dialog.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogGestureTest {
    @Test
    fun replyRequiresACompleteDragInTheReplyDirection() {
        assertFalse(replySwipeReady(-80f, 64f))
        assertFalse(replySwipeReady(63f, 64f))
        assertTrue(replySwipeReady(64f, 64f))
        assertTrue(replySwipeReady(100f, 64f))
        assertFalse(replySwipeReady(0f, 0f))
        assertFalse(replySwipeReady(10f, 0f))
    }
}
