package org.monogram.feature.dialog

import org.monogram.core.models.StickerPack

internal sealed interface PickerGridCell {
    val key: String
    val span: Boolean
    val documentId: Long?
}

internal data class PickerHeaderCell(
    override val key: String,
    val category: SystemEmojiCategoryKind? = null,
    val packId: Long? = null,
    val title: String = "",
    val recent: Boolean = false,
) : PickerGridCell {
    override val span: Boolean = true
    override val documentId: Long? = null
}

internal data class PickerGlyphCell(
    val glyph: String,
    val index: Int,
    val section: String = "",
) : PickerGridCell {
    override val key: String =
        if (section.isEmpty()) "glyph:$index:$glyph" else "glyph:$section:$index:$glyph"
    override val span: Boolean = false
    override val documentId: Long? = null
}

internal data class PickerDocumentCell(
    val packId: Long,
    override val documentId: Long,
    val occurrence: Int = 1,
) : PickerGridCell {
    override val key: String =
        if (occurrence <= 1) "doc:$packId:$documentId" else "doc:$packId:$documentId:$occurrence"
    override val span: Boolean = false
}

internal data class PickerPlaceholderCell(
    val packId: Long,
    val index: Int,
    val failed: Boolean,
) : PickerGridCell {
    override val key: String = "ph:$packId:$index:$failed"
    override val span: Boolean = false
    override val documentId: Long? = null
}

internal data class PickerExpandCell(
    val packId: Long,
) : PickerGridCell {
    override val key: String = "expand:$packId"
    override val span: Boolean = false
    override val documentId: Long? = null
}

internal data class EmojiPanelLayout(
    val cells: List<PickerGridCell>,
    val categoryStart: Map<SystemEmojiCategoryKind, Int>,
    val packStart: Map<Long, Int>,
)

internal fun emojiPanelLayout(
    recent: List<String>,
    categories: List<SystemEmojiCategory>,
    packs: List<StickerPack>,
    loaded: Map<Long, StickerPack>,
    expanded: Set<Long>,
    loadingPackIds: Set<Long>,
    failedPackIds: Set<Long>,
): EmojiPanelLayout {
    val cells = ArrayList<PickerGridCell>()
    val categoryStart = LinkedHashMap<SystemEmojiCategoryKind, Int>()
    val packStart = LinkedHashMap<Long, Int>()
    if (recent.isNotEmpty()) {
        cells += PickerHeaderCell(key = "recent", recent = true)
        recent.forEachIndexed { index, glyph ->
            cells += PickerGlyphCell(glyph, index, section = "recent")
        }
    }
    categories.forEach { category ->
        categoryStart[category.kind] = cells.size
        cells += PickerHeaderCell(key = "cat:${category.kind}", category = category.kind)
        category.glyphs.forEachIndexed { index, glyph ->
            cells += PickerGlyphCell(glyph, index, section = category.kind.name)
        }
    }
    packs.forEach { pack ->
        packStart[pack.id] = cells.size
        cells += PickerHeaderCell(key = "pack:${pack.id}", packId = pack.id, title = pack.title)
        val documents = loaded[pack.id]?.previewDocumentIds.orEmpty()
        when {
            pack.id in expanded && documents.isNotEmpty() ->
                cells.addDocuments(pack.id, documents)

            pack.id in expanded -> {
                val count = pack.count.coerceAtLeast(1)
                val failed = pack.id in failedPackIds && pack.id !in loadingPackIds
                repeat(count) { index ->
                    cells += PickerPlaceholderCell(pack.id, index, failed = failed)
                }
            }

            else -> cells += PickerExpandCell(pack.id)
        }
    }
    return EmojiPanelLayout(cells, categoryStart, packStart)
}

internal fun stickerPanelCells(
    packs: List<StickerPack>,
    loaded: Map<Long, StickerPack>,
    failedPackIds: Set<Long>,
): List<PickerGridCell> = buildList {
    packs.forEach { pack ->
        add(PickerHeaderCell(key = "pack:${pack.id}", packId = pack.id, title = pack.title))
        val documents = loaded[pack.id]?.previewDocumentIds
        when {
            !documents.isNullOrEmpty() -> addDocuments(pack.id, documents)
            else -> {
                val failed = pack.id in failedPackIds
                repeat(pack.count.coerceAtLeast(1)) { index ->
                    add(PickerPlaceholderCell(pack.id, index, failed = failed))
                }
            }
        }
    }
}

private fun MutableList<PickerGridCell>.addDocuments(packId: Long, documents: List<Long>) {
    val seen = HashMap<Long, Int>()
    documents.forEach { documentId ->
        val occurrence = (seen[documentId] ?: 0) + 1
        seen[documentId] = occurrence
        add(PickerDocumentCell(packId, documentId, occurrence = occurrence))
    }
}

internal const val GifItemContentType = "gif"

internal fun pickerGridContentType(
    cell: PickerGridCell,
    stickers: Boolean,
    animating: Boolean = false,
): String {
    val id = when (cell) {
        is PickerHeaderCell -> "header"
        is PickerGlyphCell -> "system-emoji"
        is PickerDocumentCell -> if (stickers) "sticker" else "custom-emoji"
        is PickerPlaceholderCell -> "loading-error"
        is PickerExpandCell -> "expansion"
    }
    return if (animating) id else id
}

internal data class PickerSectionMark(
    val index: Int,
    val category: SystemEmojiCategoryKind? = null,
    val packId: Long? = null,
)

internal fun pickerSectionMarks(cells: List<PickerGridCell>): List<PickerSectionMark> =
    cells.mapIndexedNotNull { index, cell ->
        val header = cell as? PickerHeaderCell ?: return@mapIndexedNotNull null
        PickerSectionMark(index, header.category, header.packId)
    }

internal fun activePickerSection(
    marks: List<PickerSectionMark>,
    firstVisibleIndex: Int,
    lastIndex: Int,
): PickerSectionMark? {
    if (marks.isEmpty() || lastIndex < 0 || firstVisibleIndex < 0) return null
    val target = firstVisibleIndex.coerceAtMost(lastIndex)
    var low = 0
    var high = marks.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (marks[mid].index <= target) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return marks.getOrNull(found)
}
