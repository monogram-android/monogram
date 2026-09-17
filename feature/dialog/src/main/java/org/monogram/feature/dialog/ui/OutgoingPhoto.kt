package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

internal const val OUTGOING_PHOTO_MAX_SIDE = 2560
internal const val OUTGOING_PHOTO_LIMIT = 10 * 1024 * 1024

private val OUTGOING_PHOTO_QUALITIES = intArrayOf(99, 92, 86, 80)
private const val OUTGOING_PHOTO_MIN_SIDE = 640

internal class PreparedPhoto(val file: File, val width: Int, val height: Int)

internal fun prepareOutgoingPhoto(source: File, destination: File): PreparedPhoto? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    val sourceLongest = maxOf(bounds.outWidth, bounds.outHeight)
    if (sourceLongest <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = decodeSampleSize(sourceLongest, OUTGOING_PHOTO_MAX_SIDE)
    }
    val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return null
    val fitted = fitWithin(decoded, OUTGOING_PHOTO_MAX_SIDE)
    if (fitted !== decoded) decoded.recycle()
    val oriented = applyExifRotation(source, fitted)
    if (oriented !== fitted) fitted.recycle()

    var current = oriented
    var side = OUTGOING_PHOTO_MAX_SIDE
    while (true) {
        for (quality in OUTGOING_PHOTO_QUALITIES) {
            if (compressTo(destination, current, quality) && destination.length() <= OUTGOING_PHOTO_LIMIT) {
                val width = current.width
                val height = current.height
                current.recycle()
                return PreparedPhoto(destination, width, height)
            }
        }
        if (side <= OUTGOING_PHOTO_MIN_SIDE) break
        side /= 2
        val smaller = fitWithin(current, side)
        if (smaller !== current) current.recycle()
        current = smaller
    }
    current.recycle()
    destination.delete()
    return null
}

internal fun reencodablePhoto(mime: String, name: String): Boolean {
    val type = mime.lowercase()
    if (type.isNotEmpty()) return type.startsWith("image/") && type != "image/gif"
    val lower = name.lowercase()
    return lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".bmp") ||
        lower.endsWith(".heic") ||
        lower.endsWith(".heif")
}

private fun compressTo(destination: File, bitmap: Bitmap, quality: Int): Boolean = runCatching {
    destination.outputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    }
}.getOrDefault(false)

private fun decodeSampleSize(longest: Int, target: Int): Int {
    var sample = 1
    while (longest / (sample * 2) >= target) sample *= 2
    return sample
}

private fun fitWithin(bitmap: Bitmap, target: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= target) return bitmap
    val scale = target.toFloat() / longest
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
    val rotation = runCatching {
        @Suppress("DEPRECATION")
        when (
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotation == 0f) return bitmap
    val matrix = Matrix().apply { postRotate(rotation) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
