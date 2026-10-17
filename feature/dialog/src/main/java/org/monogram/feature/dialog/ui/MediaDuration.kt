package org.monogram.feature.dialog.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.monogram.feature.dialog.R
import org.monogram.network.http.MediaPriority
import org.monogram.core.models.isStickerFileName
import org.monogram.core.ui.AutoDownloadPreset
import org.monogram.core.ui.DownloadSettings

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    return if (mb < 1024) "%.1f MB".format(mb) else "%.1f GB".format(mb / 1024.0)
}

internal fun downloadProgressFraction(bytes: Long, total: Long?): Float? {
    if (total == null || total <= 0L || bytes < 0L) return null
    return (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

internal fun formatDownloadProgress(bytes: Long, total: Long?): String? {
    val knownTotal = total?.takeIf { it > 0L }
    return when {
        knownTotal != null ->
            "${formatFileSize(bytes.coerceAtLeast(0L))} / ${formatFileSize(knownTotal)}"
        bytes > 0L -> formatFileSize(bytes)
        else -> null
    }
}

internal fun downloadBytesPerSecond(previous: Long, current: Long, elapsedMs: Long): Long =
    if (elapsedMs <= 0L || current < previous) 0L
    else ((current - previous).toDouble() * 1000.0 / elapsedMs).toLong()

@Composable
internal fun downloadProgressText(bytes: Long, total: Long?, active: Boolean = true): String? {
    val latestBytes by rememberUpdatedState(bytes)
    var speed by remember(active) { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            speed = 0L
            var previousBytes = latestBytes
            var previousTime = SystemClock.elapsedRealtime()
            while (true) {
                delay(1000L)
                val now = SystemClock.elapsedRealtime()
                val received = latestBytes
                speed = downloadBytesPerSecond(previousBytes, received, now - previousTime)
                previousBytes = received
                previousTime = now
            }
        }
    }
    val progress = formatDownloadProgress(bytes, total) ?: return null
    return if (active) {
        stringResource(R.string.dialog_media_progress_speed, progress, formatFileSize(speed))
    } else {
        progress
    }
}

internal fun formatMediaDuration(seconds: Int): String {
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remain = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remain)
    } else {
        "%d:%02d".format(minutes, remain)
    }
}

internal fun mediaAspectRatio(width: Int?, height: Int?): Float? {
    if (width == null || height == null || width <= 0 || height <= 0) return null
    return (width.toFloat() / height.toFloat()).coerceIn(0.4f, 2.5f)
}

internal const val PHOTO_MIN_DP = 168
internal const val PHOTO_MAX_WIDTH_DP = 280
internal const val PHOTO_MAX_HEIGHT_DP = 320
internal const val PHOTO_DEFAULT_ASPECT = 4f / 3f
internal const val VIDEO_DEFAULT_ASPECT = 16f / 9f
internal const val VIDEO_NOTE_DP = 192

/** Widest bubble body; edge-to-edge media spans exactly this. */
internal const val BUBBLE_MAX_WIDTH_DP = 324

/** Bubble photo size: never a tiny thumb, never full-row stretch. */
internal fun photoDisplaySize(
    width: Int?,
    height: Int?,
    fallbackAspect: Float = PHOTO_DEFAULT_ASPECT,
    maxWidthDp: Int = PHOTO_MAX_WIDTH_DP,
    maxHeightDp: Int = PHOTO_MAX_HEIGHT_DP,
): Pair<Int, Int> {
    val aspect = mediaAspectRatio(width, height) ?: fallbackAspect.coerceIn(0.4f, 2.5f)
    var w = maxWidthDp.toFloat()
    var h = w / aspect
    if (h > maxHeightDp) {
        h = maxHeightDp.toFloat()
        w = h * aspect
    }
    if (w < PHOTO_MIN_DP) {
        w = PHOTO_MIN_DP.toFloat()
        h = (w / aspect).coerceAtMost(maxHeightDp.toFloat())
    }
    if (h < PHOTO_MIN_DP) {
        h = PHOTO_MIN_DP.toFloat()
        w = (h * aspect).coerceIn(PHOTO_MIN_DP.toFloat(), maxWidthDp.toFloat())
    }
    return w.toInt().coerceAtLeast(PHOTO_MIN_DP) to h.toInt().coerceAtLeast(PHOTO_MIN_DP)
}

internal fun visualMediaDisplaySize(kind: String?, width: Int?, height: Int?): Pair<Int, Int> =
    if (kind == "video_note") VIDEO_NOTE_DP to VIDEO_NOTE_DP
    else photoDisplaySize(width, height, mediaFallbackAspect(kind))

/**
 * Media drawn edge-to-edge in its bubble: full bubble width, still aspect-correct and
 * capped in height. The bubble clip rounds the corners, so no inner shape is applied.
 */
internal fun bubbleEdgeMediaDisplaySize(kind: String?, width: Int?, height: Int?): Pair<Int, Int> =
    photoDisplaySize(
        width = width,
        height = height,
        fallbackAspect = mediaFallbackAspect(kind),
        maxWidthDp = BUBBLE_MAX_WIDTH_DP,
    )

internal fun isEdgeMediaKind(kind: String?): Boolean = when (kind) {
    "photo", "video", "gif" -> true
    else -> false
}

internal fun shouldOverlayMediaMeta(
    stickerOnly: Boolean,
    edgeVisualMedia: Boolean,
    mediaCaption: Boolean,
): Boolean = stickerOnly || (edgeVisualMedia && !mediaCaption)

internal fun shouldBleedMediaBottom(
    edgeMedia: Boolean,
    mediaCaption: Boolean,
    hasComments: Boolean,
): Boolean = edgeMedia && !mediaCaption && !hasComments

private fun mediaFallbackAspect(kind: String?): Float = when (kind) {
    "video", "gif" -> VIDEO_DEFAULT_ASPECT
    else -> PHOTO_DEFAULT_ASPECT
}

internal fun shouldShowMessageCaption(kind: String?, text: String?, fileName: String? = null): Boolean {
    if (text.isNullOrBlank()) return false
    if (isStickerMedia(kind, text)) {
        if (isStickerFileName(text)) return false
        if (isStickerAltText(text)) return false
    }
    if (kind == "document" && fileName != null && text == fileName) return false
    return true
}

/** DocumentAttributeSticker.alt is an emoji, not a user caption. */
internal fun isStickerAltText(text: String?): Boolean {
    val value = text?.trim().orEmpty()
    if (value.isEmpty() || value.length > 16) return false
    return value.none { it.isLetterOrDigit() }
}

internal fun mediaFullPriority(userRequested: Boolean): Int =
    if (userRequested) MediaPriority.USER
    else MediaPriority.VISIBLE

internal fun shouldAutoFetchFullMedia(
    kind: String?,
    userRequested: Boolean,
    sizeBytes: Long? = null,
    preset: AutoDownloadPreset = DownloadSettings.activePreset(),
    autoplayVideos: Boolean = true,
    autoplayGifs: Boolean = true,
): Boolean {
    if (!userRequested) {
        if (kind == "video" && !autoplayVideos) return false
        if (kind == "gif" && !autoplayGifs) return false
    }
    return preset.allowsFull(kind, sizeBytes, userRequested)
}

internal fun shouldAutoFetchDisplayMedia(
    kind: String?,
    sizeBytes: Long? = null,
    preset: AutoDownloadPreset = DownloadSettings.activePreset(),
): Boolean = preset.allowsDisplay(kind, sizeBytes)

/** Display preview is skipped when full auto-download will fetch the same bytes. */
internal fun shouldFetchDisplayPreview(
    kind: String?,
    userRequested: Boolean,
    sizeBytes: Long? = null,
    preset: AutoDownloadPreset = DownloadSettings.activePreset(),
    autoplayVideos: Boolean = true,
    autoplayGifs: Boolean = true,
): Boolean {
    if (shouldAutoFetchFullMedia(
            kind,
            userRequested,
            sizeBytes,
            preset,
            autoplayVideos,
            autoplayGifs,
        )
    ) {
        return false
    }
    return shouldAutoFetchDisplayMedia(kind, sizeBytes, preset)
}

/** Detach may cancel this message's fetches unless the user or viewer owns them. */
internal fun shouldCancelOnViewportDetach(
    visible: Boolean,
    userRequested: Boolean,
    viewerOpen: Boolean,
): Boolean = !visible && !userRequested && !viewerOpen

internal fun shouldAutoplayChatVideo(
    kind: String?,
    supportsStreaming: Boolean,
    sizeBytes: Long?,
    visible: Boolean,
    autoplayVideos: Boolean,
    preset: AutoDownloadPreset,
): Boolean {
    if (!visible || !autoplayVideos || kind != "video" || !supportsStreaming) return false
    return preset.allowsStream(kind, sizeBytes)
}

/** Stripped/inline thumbs live on a different cache key than the original. */
internal fun hasDistinctMediaThumb(thumbCacheKey: String?, fullCacheKey: String?): Boolean =
    !thumbCacheKey.isNullOrBlank() && thumbCacheKey != fullCacheKey

/** Skip getFile thumbs when a file or in-memory stripped JPEG is already available. */
internal fun shouldFetchMessageThumb(
    kind: String?,
    hasThumbFile: Boolean,
    strippedJpeg: ByteArray?,
    thumbCacheKey: String?,
    fullCacheKey: String?,
): Boolean {
    if (hasThumbFile || strippedJpeg?.isNotEmpty() == true) return false
    if (kind == "audio" || kind == "voice") return false
    if (kind == "document" && !hasDistinctMediaThumb(thumbCacheKey, fullCacheKey)) return false
    return true
}

/** Blur the tiny preview until a display/full still exists. */
internal fun shouldBlurMediaPreview(
    thumbCacheKey: String?,
    fullCacheKey: String?,
    hasSharp: Boolean,
): Boolean = hasDistinctMediaThumb(thumbCacheKey, fullCacheKey) && !hasSharp

internal fun shouldShowMediaPreviewSpinner(
    blurred: Boolean,
    fetchDone: Boolean,
    failed: Boolean,
): Boolean = blurred && !fetchDone && !failed

internal fun isStickerMedia(kind: String?, text: String?): Boolean {
    val k = kind ?: return false
    if (k == "sticker" || k == "sticker_animated" || k == "sticker_video") return true
    if (k != "document") return false
    return isStickerFileName(text)
}

internal const val STICKER_TARGET_DP = 160

internal fun stickerDisplaySize(width: Int?, height: Int?): Pair<Int, Int> {
    val w = (width ?: 512).coerceAtLeast(1)
    val h = (height ?: 512).coerceAtLeast(1)
    val scale = STICKER_TARGET_DP.toFloat() / maxOf(w, h).toFloat()
    return (w * scale).toInt().coerceIn(96, 192) to (h * scale).toInt().coerceIn(96, 192)
}
