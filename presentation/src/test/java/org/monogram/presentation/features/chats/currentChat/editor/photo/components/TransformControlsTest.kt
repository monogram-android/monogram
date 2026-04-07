package org.monogram.presentation.features.chats.currentChat.editor.photo.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TransformControlsTest {
    @Test
    fun `rotateClockwiseAnimationTarget always moves clockwise to next quarter turn`() {
        assertEquals(90f, rotateClockwiseAnimationTarget(10f), 0.001f)
        assertEquals(90f, rotateClockwiseAnimationTarget(-10f), 0.001f)
        assertEquals(270f, rotateClockwiseAnimationTarget(179f), 0.001f)
        assertEquals(450f, rotateClockwiseAnimationTarget(350f), 0.001f)
    }

    @Test
    fun `rotateClockwiseToNextRightAngle advances exact quarter turn`() {
        assertEquals(90f, rotateClockwiseToNextRightAngle(0f), 0.001f)
        assertEquals(180f, rotateClockwiseToNextRightAngle(90f), 0.001f)
        assertEquals(-90f, rotateClockwiseToNextRightAngle(180f), 0.001f)
    }

    @Test
    fun `rotateClockwiseToNextRightAngle snaps tilted image before rotating`() {
        assertEquals(90f, rotateClockwiseToNextRightAngle(10f), 0.001f)
        assertEquals(90f, rotateClockwiseToNextRightAngle(-10f), 0.001f)
        assertEquals(180f, rotateClockwiseToNextRightAngle(100f), 0.001f)
        assertEquals(-90f, rotateClockwiseToNextRightAngle(179f), 0.001f)
    }
}
