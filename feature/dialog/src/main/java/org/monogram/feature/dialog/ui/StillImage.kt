package org.monogram.feature.dialog.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlin.math.roundToInt

/** JPEG / PNG / GIF / WEBP / BMP magic. Extensionless video must not go to Coil. */
internal fun stillImageMagic(header: ByteArray): Boolean {
    if (header.size < 3) return false
    if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) return true
    if (header.size >= 8 &&
        header[0] == 0x89.toByte() &&
        header[1] == 0x50.toByte() &&
        header[2] == 0x4E.toByte() &&
        header[3] == 0x47.toByte()
    ) {
        return true
    }
    if (header[0] == 'G'.code.toByte() &&
        header[1] == 'I'.code.toByte() &&
        header[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (header.size >= 12 &&
        header[0] == 'R'.code.toByte() &&
        header[1] == 'I'.code.toByte() &&
        header[2] == 'F'.code.toByte() &&
        header[3] == 'F'.code.toByte() &&
        header[8] == 'W'.code.toByte() &&
        header[9] == 'E'.code.toByte() &&
        header[10] == 'B'.code.toByte() &&
        header[11] == 'P'.code.toByte()
    ) {
        return true
    }
    return header[0] == 0x42.toByte() && header[1] == 0x4D.toByte()
}

internal fun stillImageFile(file: File?): Boolean {
    if (file == null || !file.isFile) return false
    return runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(12)
            val n = stream.read(header)
            n > 0 && stillImageMagic(header.copyOf(n))
        }
    }.getOrDefault(false)
}

internal fun gzipFile(file: File?): Boolean {
    val header = fileHeader(file, 2) ?: return false
    return header.size >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
}

internal fun webmFile(file: File?): Boolean {
    val header = fileHeader(file, 4) ?: return false
    return header.size >= 4 &&
        header[0] == 0x1a.toByte() &&
        header[1] == 0x45.toByte() &&
        header[2] == 0xdf.toByte() &&
        header[3] == 0xa3.toByte()
}

internal fun videoThumbnail(file: File, maxSizePx: Int = 0): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        if (maxSizePx > 0) {
            scaledFrame(retriever, maxSizePx)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
        } else {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    } catch (_: RuntimeException) {
        null
    } finally {
        retriever.release()
    }
}

/** Decodes straight into a capped size; null when the source is small enough already. */
private fun scaledFrame(retriever: MediaMetadataRetriever, maxSizePx: Int): Bitmap? {
    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        ?.toIntOrNull() ?: return null
    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        ?.toIntOrNull() ?: return null
    if (width <= 0 || height <= 0 || (width <= maxSizePx && height <= maxSizePx)) return null
    val scale = maxSizePx.toFloat() / maxOf(width, height)
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return runCatching {
        retriever.getScaledFrameAtTime(
            0L,
            MediaMetadataRetriever.OPTION_CLOSEST,
            targetWidth,
            targetHeight,
        )
    }.getOrNull()
}

private fun fileHeader(file: File?, n: Int): ByteArray? {
    if (file == null || !file.isFile) return null
    return runCatching {
        file.inputStream().use { stream ->
            val header = ByteArray(n)
            val read = stream.read(header)
            if (read <= 0) null else header.copyOf(read)
        }
    }.getOrNull()
}
