package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Cheap stand-in blur for stripped Telegram thumbs (`photoStrippedSize`).
 * Downsample then upsample so a 40px JPEG is a color wash, not pixels.
 */
internal class PreviewBlurTransformation(
    private val downsample: Int = 4,
) : Transformation {
    override val cacheKey: String = "monogram-preview-blur-$downsample"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val factor = downsample.coerceAtLeast(2)
        val width = (input.width / factor).coerceAtLeast(1)
        val height = (input.height / factor).coerceAtLeast(1)
        if (width == input.width && height == input.height) return input
        val small = Bitmap.createScaledBitmap(input, width, height, true)
        val output = Bitmap.createScaledBitmap(small, input.width, input.height, true)
        if (small !== input && small !== output) small.recycle()
        return output
    }
}
