package org.monogram.presentation.features.chats.conversation.editor.photo.crop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {
    @Test
    fun `fitContentInBounds restores crop coverage for rotated image`() {
        val baseBounds = Rect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val cropRect = Rect(left = 15f, top = 15f, right = 85f, bottom = 85f)
        val initialScale = 1.2f
        val initialOffset = Offset(50f, -40f)

        assertFalse(
            isCropRectCoveredByImage(
                baseBounds = baseBounds,
                cropRect = cropRect,
                scale = initialScale,
                rotationDegrees = 35f,
                offset = initialOffset,
                pivot = baseBounds.center
            )
        )

        val (newScale, newOffset) = fitContentInBounds(
            baseBounds = baseBounds,
            cropRect = cropRect,
            scale = initialScale,
            rotationDegrees = 35f,
            offset = initialOffset,
            pivot = baseBounds.center,
            minScale = 0.5f,
            maxScale = 10f
        )

        assertTrue(
            isCropRectCoveredByImage(
                baseBounds = baseBounds,
                cropRect = cropRect,
                scale = newScale,
                rotationDegrees = 35f,
                offset = newOffset,
                pivot = baseBounds.center
            )
        )
    }

    @Test
    fun `isCropRectCoveredByImage rejects crop in rotated bounding box corner`() {
        val baseBounds = Rect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val visibleBounds = rotatedVisibleBounds(baseBounds)
        val cropRect = Rect(
            left = visibleBounds.left,
            top = visibleBounds.top,
            right = 65f,
            bottom = 65f
        )

        assertFalse(
            isCropRectCoveredByImage(
                baseBounds = baseBounds,
                cropRect = cropRect,
                scale = 1f,
                rotationDegrees = 45f,
                offset = Offset.Zero,
                pivot = baseBounds.center
            )
        )
    }

    @Test
    fun `constrainCropRectToImage pulls invalid rotated crop back inside image`() {
        val baseBounds = Rect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        val visibleBounds = rotatedVisibleBounds(baseBounds)
        val currentCropRect = Rect(left = 35f, top = 35f, right = 65f, bottom = 65f)
        val candidateRect = Rect(
            left = visibleBounds.left,
            top = visibleBounds.top,
            right = currentCropRect.right,
            bottom = currentCropRect.bottom
        )

        val constrained = constrainCropRectToImage(
            currentCropRect = currentCropRect,
            candidateRect = candidateRect,
            visibleBounds = visibleBounds,
            minCropSizePx = 16f,
            baseBounds = baseBounds,
            scale = 1f,
            rotationDegrees = 45f,
            offset = Offset.Zero,
            pivot = baseBounds.center
        )

        assertTrue(constrained.left > candidateRect.left + EPSILON)
        assertTrue(constrained.top > candidateRect.top + EPSILON)
        assertTrue(
            isCropRectCoveredByImage(
                baseBounds = baseBounds,
                cropRect = constrained,
                scale = 1f,
                rotationDegrees = 45f,
                offset = Offset.Zero,
                pivot = baseBounds.center
            )
        )
    }

    @Test
    fun `quarter turn transformed bounds stay covered by image`() {
        val baseBounds = Rect(left = 0f, top = 0f, right = 200f, bottom = 100f)
        val pivot = baseBounds.center
        val cropRect = calculateScalarTransformedBounds(
            baseBounds = baseBounds,
            scale = 1f,
            rotationDegrees = 90f,
            offset = Offset.Zero,
            pivot = pivot
        )

        assertTrue(
            isCropRectCoveredByImage(
                baseBounds = baseBounds,
                cropRect = cropRect,
                scale = 1f,
                rotationDegrees = 90f,
                offset = Offset.Zero,
                pivot = pivot
            )
        )
    }

    private fun rotatedVisibleBounds(baseBounds: Rect): Rect {
        return calculateScalarTransformedBounds(
            baseBounds = baseBounds,
            scale = 1f,
            rotationDegrees = 45f,
            offset = Offset.Zero,
            pivot = baseBounds.center
        )
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
