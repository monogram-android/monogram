package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.core.markup.isRenderableLatex
import ru.noties.jlatexmath.JLatexMathDrawable

private val latexRenderMutex = Mutex()

/** Serializes the renderer's shared font caches; callers run on a worker dispatcher. */
internal suspend fun renderLatexBitmap(
    source: String,
    display: Boolean,
    color: Int,
    textSizePx: Float,
    maxWidthPx: Int,
): ImageBitmap? {
    if (!isRenderableLatex(source) || !textSizePx.isFinite() || textSizePx <= 0) return null
    return latexRenderMutex.withLock {
        try {
            val drawable = JLatexMathDrawable.builder(if (display) source else "\\textstyle $source")
                .textSize(textSizePx)
                .color(color)
                .padding(2)
                .build()
            val width = drawable.intrinsicWidth
            val height = drawable.intrinsicHeight
            if (width !in 1..maxWidthPx.coerceAtMost(2048) || height !in 1..512) {
                return@withLock null
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.draw(Canvas(bitmap))
            }.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}
