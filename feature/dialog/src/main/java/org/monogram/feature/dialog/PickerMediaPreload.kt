package org.monogram.feature.dialog

import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack
import org.monogram.network.http.MediaPriority
import org.monogram.network.http.mediaThumbCacheKey

object PickerMediaPreload {
    const val THUMB_RADIUS = 24
    const val DOCUMENT_RADIUS = 8

    enum class Fetch { Thumb, Document }

    enum class DocKind { Unknown, Tgs, Webm, Webp, Gif }

    data class Item(
        val documentId: Long,
        val cacheKey: String,
        val thumbCacheKey: String? = null,
        val gif: Boolean = false,
        val kind: DocKind = if (gif) DocKind.Gif else DocKind.Unknown,
    )

    data class Task(
        val item: Item,
        val fetch: Fetch,
        val priority: Int,
        val cacheKey: String,
    )

    data class Plan(
        val visibleRange: IntRange,
        val window: IntRange,
        val tasks: List<Task>,
    ) {
        val keys: Set<String> get() = tasks.mapTo(LinkedHashSet()) { it.cacheKey }

        fun signature(): String = buildString {
            append(visibleRange.first)
            append(':')
            append(visibleRange.last)
            append('|')
            append(window.first)
            append(':')
            append(window.last)
            append('|')
            tasks.forEach { task ->
                append(task.cacheKey)
                append(':')
                append(task.fetch.name)
                append(':')
                append(task.priority)
                append(',')
            }
        }
    }

    fun documents(ids: List<Long>): List<Item> =
        ids.map { id -> Item(documentId = id, cacheKey = "emoji:$id") }

    fun gifs(gifs: List<SavedGif>): List<Item> =
        gifs.map { gif ->
            Item(
                documentId = gif.documentId,
                cacheKey = gif.cacheKey,
                thumbCacheKey = gif.thumbCacheKey ?: mediaThumbCacheKey(gif.cacheKey),
                gif = true,
            )
        }

    fun packSlots(
        packs: List<StickerPack>,
        loadedPacks: Map<Long, StickerPack>,
    ): List<Long?> = buildList {
        packs.forEach { pack ->
            add(null)
            val docs = loadedPacks[pack.id]?.previewDocumentIds
            if (docs != null) {
                docs.forEach { add(it) }
            } else {
                repeat(pack.count.coerceAtLeast(1)) { add(null) }
            }
        }
    }

    fun visibleIds(slots: List<Long?>, gridMin: Int, gridMax: Int): Set<Long> {
        if (slots.isEmpty() || gridMax < gridMin) return emptySet()
        val start = gridMin.coerceIn(0, slots.lastIndex)
        val end = gridMax.coerceIn(0, slots.lastIndex)
        return slots.subList(start, end + 1).mapNotNull { it }.toSet()
    }

    fun plan(
        items: List<Item>,
        visibleDocumentIds: Set<Long>,
        thumbRadius: Int = THUMB_RADIUS,
        documentRadius: Int = DOCUMENT_RADIUS,
        idle: Boolean = false,
    ): Plan {
        if (items.isEmpty()) {
            return Plan(IntRange.EMPTY, IntRange.EMPTY, emptyList())
        }
        val visibleIndices = items.mapIndexedNotNull { index, item ->
            index.takeIf { item.documentId in visibleDocumentIds }
        }
        val visibleRange = if (visibleIndices.isEmpty()) {
            0..0
        } else {
            visibleIndices.min()..visibleIndices.max()
        }
        val window = expanded(visibleRange, thumbRadius, items.lastIndex)
        val documents = expanded(visibleRange, documentRadius, items.lastIndex)
        val tasks = ArrayList<Task>()
        for (index in window) {
            val item = items[index]
            val visible = index in visibleRange
            if (item.gif) {
                val thumbKey = item.thumbCacheKey ?: mediaThumbCacheKey(item.cacheKey)
                tasks += Task(
                    item = item,
                    fetch = Fetch.Thumb,
                    priority = if (visible) MediaPriority.DEFAULT else MediaPriority.IDLE,
                    cacheKey = thumbKey,
                )
                if (idle && visible) {
                    tasks += Task(
                        item = item,
                        fetch = Fetch.Document,
                        priority = MediaPriority.DEFAULT,
                        cacheKey = item.cacheKey,
                    )
                }
            } else if (index in documents) {
                val priority = when {
                    visible -> MediaPriority.VISIBLE
                    item.kind == DocKind.Webm -> MediaPriority.DEFAULT
                    else -> MediaPriority.IDLE
                }
                tasks += Task(
                    item = item,
                    fetch = Fetch.Document,
                    priority = priority,
                    cacheKey = item.cacheKey,
                )
            } else if (item.thumbCacheKey != null) {
                tasks += Task(
                    item = item,
                    fetch = Fetch.Thumb,
                    priority = if (visible) MediaPriority.DEFAULT else MediaPriority.IDLE,
                    cacheKey = item.thumbCacheKey,
                )
            }
        }
        tasks.sortWith(
            compareByDescending<Task> { it.priority }
                .thenByDescending { kindRank(it.item.kind) },
        )
        return Plan(visibleRange, window, tasks)
    }

    private fun expanded(range: IntRange, radius: Int, lastIndex: Int): IntRange {
        if (range.isEmpty()) return IntRange.EMPTY
        return (range.first - radius).coerceAtLeast(0)..(range.last + radius).coerceAtMost(lastIndex)
    }

    private fun kindRank(kind: DocKind): Int = when (kind) {
        DocKind.Webm -> 2
        DocKind.Tgs -> 0
        else -> 1
    }
}
