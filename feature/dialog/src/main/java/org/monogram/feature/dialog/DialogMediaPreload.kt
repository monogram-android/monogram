package org.monogram.feature.dialog

import org.monogram.core.models.Message
import org.monogram.core.models.WebpagePreviews
import org.monogram.core.ui.AutoDownloadPreset
import org.monogram.core.ui.DownloadSettings
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.photoDisplayCacheKey

object DialogMediaPreload {
    const val WINDOW_RADIUS = 4

    private val PRELOAD_KINDS = setOf(
        "photo",
        "webpage",
        "video",
        "gif",
        "sticker",
        "sticker_animated",
        "sticker_video",
        "document",
    )
    private val STICKER_KINDS = setOf("sticker", "sticker_animated", "sticker_video")

    enum class Fetch { Thumb, Display, Full }

    data class MediaTask(
        val message: Message,
        val fetch: Fetch,
        val priority: Int,
        val cacheKey: String,
    )

    data class InstantViewTask(
        val url: String,
        val hash: Int,
        val visible: Boolean,
    )

    data class Plan(
        val visibleRange: IntRange,
        val window: IntRange,
        val media: List<MediaTask>,
        val instantViews: List<InstantViewTask>,
    ) {
        val mediaKeys: Set<String> get() = media.mapTo(LinkedHashSet()) { it.cacheKey }

        fun signature(): String = buildString {
            append(visibleRange.first)
            append(':')
            append(visibleRange.last)
            append('|')
            append(window.first)
            append(':')
            append(window.last)
            append('|')
            media.forEach { task ->
                append(task.cacheKey)
                append(':')
                append(task.fetch.name)
                append(':')
                append(task.priority)
                append(',')
            }
            instantViews.forEach { page ->
                append(page.url)
                append('#')
                append(page.hash)
                append(',')
            }
        }
    }

    fun plan(
        messages: List<Message>,
        visibleIds: Set<Int>,
        radius: Int = WINDOW_RADIUS,
        preset: AutoDownloadPreset = DownloadSettings.activePreset(),
    ): Plan {
        if (messages.isEmpty()) {
            return Plan(IntRange.EMPTY, IntRange.EMPTY, emptyList(), emptyList())
        }
        val knownVisible = visibleIds.filter { id -> messages.any { it.id.id == id } }.toSet()
            .ifEmpty { setOf(messages.first().id.id) }
        val visibleIndices = messages.mapIndexedNotNull { index, message ->
            index.takeIf { message.id.id in knownVisible }
        }
        if (visibleIndices.isEmpty()) {
            return Plan(IntRange.EMPTY, IntRange.EMPTY, emptyList(), emptyList())
        }
        val visibleRange = visibleIndices.min()..visibleIndices.max()
        val window = (visibleRange.first - radius).coerceAtLeast(0)..
            (visibleRange.last + radius).coerceAtMost(messages.lastIndex)
        val media = ArrayList<MediaTask>()
        val instantViews = ArrayList<InstantViewTask>()
        val seenIv = HashSet<String>()
        for (index in window) {
            val message = messages[index]
            val kind = message.mediaKind
            val visible = index in visibleRange
            val priority = if (visible) MediaPriority.VISIBLE else MediaPriority.DEFAULT
            if (kind in PRELOAD_KINDS) {
                val distinctThumb = message.thumbCacheKey
                    ?.takeUnless { it == message.mediaCacheKey }
                // Documents have no synthetic thumb. Prefetch only a real preview.
                val thumbKey = when {
                    kind == "document" -> distinctThumb
                    distinctThumb != null -> distinctThumb
                    else -> message.mediaCacheKey?.let { "$it:thumb" }
                }
                if (!thumbKey.isNullOrBlank()) {
                    val thumbPriority = if (visible) MediaPriority.THUMB else MediaPriority.IDLE
                    media += MediaTask(message, Fetch.Thumb, thumbPriority, thumbKey)
                }
                val fullKey = message.mediaCacheKey
                val sticker = kind in STICKER_KINDS
                val size = message.fileSize
                val wantFull = when {
                    sticker -> visible
                    else -> preset.allowsFull(kind, size)
                }
                if (!fullKey.isNullOrBlank() && wantFull) {
                    media += MediaTask(message, Fetch.Full, priority, fullKey)
                } else if (visible && !fullKey.isNullOrBlank() && preset.allowsDisplay(kind, size)) {
                    media += MediaTask(
                        message,
                        Fetch.Display,
                        MediaPriority.VISIBLE,
                        photoDisplayCacheKey(fullKey),
                    )
                }
            }
            if (kind == "webpage") {
                val preview = WebpagePreviews.parse(message.fileName)
                if (preview != null && preview.offersInstantView && seenIv.add(preview.url)) {
                    instantViews += InstantViewTask(preview.url, preview.hash, visible)
                }
            }
        }
        media.sortByDescending { it.priority }
        instantViews.sortByDescending { it.visible }
        return Plan(visibleRange, window, media, instantViews)
    }
}
