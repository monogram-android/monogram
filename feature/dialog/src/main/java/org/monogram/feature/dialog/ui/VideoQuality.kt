package org.monogram.feature.dialog.ui

import kotlin.math.min
import kotlin.math.roundToInt

internal const val VIDEO_TARGET_MAX_SIDE = 1280
private const val VIDEO_COMPRESS_SKIP_BYTES = 1000L * 1024L * 1024L
private const val VIDEO_BITRATE_BASE = 2_000_000f * 1.13f

internal class VideoInfo(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val sizeBytes: Long,
)

internal class VideoTarget(val width: Int, val height: Int, val bitrate: Int)

internal fun videoTarget(info: VideoInfo): VideoTarget? {
    if (info.sizeBytes >= VIDEO_COMPRESS_SKIP_BYTES) return null
    if (info.width <= 0 || info.height <= 0) return null
    val longest = maxOf(info.width, info.height)
    if (longest <= VIDEO_TARGET_MAX_SIDE) return null
    val scale = VIDEO_TARGET_MAX_SIDE.toFloat() / longest
    val width = ((info.width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    val height = ((info.height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    return VideoTarget(width, height, makeVideoBitrate(info.height, info.width, info.bitrate, height, width))
}

internal fun makeVideoBitrate(
    originalHeight: Int,
    originalWidth: Int,
    originalBitrate: Int,
    height: Int,
    width: Int,
): Int {
    if (originalHeight <= 0 || originalWidth <= 0 || height <= 0 || width <= 0) return originalBitrate
    val minSide = min(height, width)
    val maxBitrate: Int
    val compressFactor: Float
    val minCompressFactor: Float
    when {
        minSide >= 1080 -> {
            maxBitrate = 6_800_000
            compressFactor = 1f
            minCompressFactor = 1f
        }

        minSide >= 720 -> {
            maxBitrate = 2_600_000
            compressFactor = 1f
            minCompressFactor = 1f
        }

        minSide >= 480 -> {
            maxBitrate = 1_000_000
            compressFactor = 0.75f
            minCompressFactor = 0.9f
        }

        else -> {
            maxBitrate = 750_000
            compressFactor = 0.6f
            minCompressFactor = 0.7f
        }
    }
    val downscale = min(originalHeight / height.toFloat(), originalWidth / width.toFloat())
    var remeasured = (originalBitrate / downscale).toInt()
    remeasured = (remeasured * compressFactor).toInt()
    val minBitrate = (minCompressFactor * VIDEO_BITRATE_BASE / (1280f * 720f / (width * height))).toInt()
    if (originalBitrate < minBitrate) return remeasured
    if (remeasured > maxBitrate) return maxBitrate
    return maxOf(remeasured, minBitrate)
}
