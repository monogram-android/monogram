package org.monogram.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.caverock.androidsvg.SVG
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.*
import kotlinx.coroutines.ensureActive
import org.monogram.core.models.Wallpaper

internal suspend fun renderWallpaper(wallpaper: Wallpaper, document: File?, destination: File) {
    val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
    val temp = File(destination.path + ".tmp")
    try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        drawWallpaperFill(canvas, wallpaper.colors, wallpaper.rotation)
        if (document != null) {
            coroutineContext.ensureActive()
            val layer = if (wallpaper.mimeType == "application/x-tgwallpattern") {
                val bytes = GZIPInputStream(document.inputStream()).use { readWallpaperPattern(it) }
                val svg = SVG.getFromInputStream(bytes.inputStream())
                Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888).also { pattern ->
                    try { svg.renderToCanvas(Canvas(pattern), RectF(0f, 0f, 1080f, 1920f)) }
                    catch (e: Exception) { pattern.recycle(); throw e }
                }
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(document.path, options)
                val sample = wallpaperSampleSize(options.outWidth, options.outHeight)
                requireNotNull(BitmapFactory.decodeFile(document.path, BitmapFactory.Options().apply { inSampleSize = sample }))
            }
            try {
                val scale = max(1080f / layer.width, 1920f / layer.height)
                val width = layer.width * scale
                val height = layer.height * scale
                val rect = RectF((1080 - width) / 2, (1920 - height) / 2, (1080 + width) / 2, (1920 + height) / 2)
                val intensity = (wallpaper.intensity ?: 50).coerceIn(-100, 100)
                if (wallpaper.pattern && intensity < 0) {
                    // Negative intensity keeps the fill only inside the pattern mask.
                    paint.alpha = abs(intensity) * 255 / 100
                    paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                    canvas.drawBitmap(layer, null, rect, paint)
                    paint.xfermode = null
                    paint.alpha = 255
                    canvas.drawColor(Color.BLACK, PorterDuff.Mode.DST_OVER)
                } else {
                    if (wallpaper.pattern) {
                        paint.alpha = intensity * 255 / 100
                        paint.colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                    }
                    canvas.drawBitmap(layer, null, rect, paint)
                }
            } finally { layer.recycle() }
        }
        if (wallpaper.blur && !wallpaper.pattern) blurWallpaper(bitmap)
        coroutineContext.ensureActive()
        temp.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
        check(temp.renameTo(destination)) { "Cannot save wallpaper" }
    } finally {
        bitmap.recycle()
        temp.delete()
    }
}

private suspend fun blurWallpaper(bitmap: Bitmap) {
    val ratio = 450f / max(bitmap.width, bitmap.height)
    val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    try {
        val width = small.width
        val height = small.height
        var pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        for (horizontal in listOf(true, false)) {
            val result = IntArray(pixels.size)
            val lines = if (horizontal) height else width
            val length = if (horizontal) width else height
            fun index(line: Int, point: Int) = if (horizontal) line * width + point else point * width + line
            for (line in 0 until lines) {
                coroutineContext.ensureActive()
                var red = 0
                var green = 0
                var blue = 0
                fun add(point: Int, sign: Int) {
                    val color = pixels[index(line, point.coerceIn(0, length - 1))]
                    red += Color.red(color) * sign
                    green += Color.green(color) * sign
                    blue += Color.blue(color) * sign
                }
                for (point in -12..12) add(point, 1)
                for (point in 0 until length) {
                    result[index(line, point)] = Color.rgb(red / 25, green / 25, blue / 25)
                    add(point - 12, -1)
                    add(point + 13, 1)
                }
            }
            pixels = result
        }
        small.setPixels(pixels, 0, width, 0, 0, width, height)
        Canvas(bitmap).drawBitmap(small, null, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
    } finally { small.recycle() }
}

private suspend fun drawWallpaperFill(canvas: Canvas, rgb: List<Int>, rotation: Int) {
    val colors = rgb.take(4).map { it or Color.BLACK }
    if (colors.size < 2) {
        canvas.drawColor(colors.firstOrNull() ?: Color.LTGRAY)
        return
    }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    if (colors.size == 2) {
        val angle = Math.toRadians(rotation.toDouble())
        val dx = sin(angle).toFloat() * canvas.width / 2
        val dy = cos(angle).toFloat() * canvas.height / 2
        paint.shader = LinearGradient(canvas.width / 2f - dx, canvas.height / 2f + dy,
            canvas.width / 2f + dx, canvas.height / 2f - dy, colors.toIntArray(), null, Shader.TileMode.CLAMP)
        canvas.drawPaint(paint)
        return
    }
    // Static freeform fill; evaluate a small field and interpolate when drawing.
    val positions = listOf(.8f to .1f, .35f to .25f, .2f to .9f, .65f to .75f)
    val pixels = IntArray(64 * 64)
    for (y in 0 until 64) {
        coroutineContext.ensureActive()
        for (x in 0 until 64) {
            val cx = x / 64f - .5f
            val cy = y / 64f - .5f
            val theta = (.35f * hypot(cx, cy)).pow(2) * 6.4f
            val px = (.5f + cx * cos(theta) - cy * sin(theta)).coerceIn(0f, 1f)
            val py = (.5f + cx * sin(theta) + cy * cos(theta)).coerceIn(0f, 1f)
            val weights = colors.indices.map { i -> max(0f, .9f - hypot(px - positions[i].first, py - positions[i].second)).pow(4) }
            val total = weights.sum().coerceAtLeast(.0001f)
            fun channel(shift: Int) = colors.indices.sumOf { i -> (((colors[i] shr shift) and 255) * weights[i] / total).toDouble() }.toInt().coerceIn(0, 255)
            pixels[y * 64 + x] = Color.rgb(channel(16), channel(8), channel(0))
        }
    }
    val fill = Bitmap.createBitmap(pixels, 64, 64, Bitmap.Config.ARGB_8888)
    try { canvas.drawBitmap(fill, null, Rect(0, 0, canvas.width, canvas.height), paint) }
    finally { fill.recycle() }
}
