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
) : PickerGridCell {
    override val key: String = "glyph:$glyph"
    override val span: Boolean = false
    override val documentId: Long? = null
}

internal data class PickerDocumentCell(
    val packId: Long,
    override val documentId: Long,
) : PickerGridCell {
    override val key: String = "doc:$packId:$documentId"
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
        recent.forEach { cells += PickerGlyphCell(it) }
    }
    categories.forEach { category ->
        categoryStart[category.kind] = cells.size
        cells += PickerHeaderCell(key = "cat:${category.kind}", category = category.kind)
        category.glyphs.forEach { cells += PickerGlyphCell(it) }
    }
    packs.forEach { pack ->
        packStart[pack.id] = cells.size
        cells += PickerHeaderCell(key = "pack:${pack.id}", packId = pack.id, title = pack.title)
        val documents = loaded[pack.id]?.previewDocumentIds.orEmpty()
        when {
            pack.id in expanded && documents.isNotEmpty() ->
                documents.forEach { cells += PickerDocumentCell(pack.id, it) }

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
            !documents.isNullOrEmpty() -> documents.forEach { add(PickerDocumentCell(pack.id, it)) }
            else -> {
                val failed = pack.id in failedPackIds
                repeat(pack.count.coerceAtLeast(1)) { index ->
                    add(PickerPlaceholderCell(pack.id, index, failed = failed))
                }
            }
        }
    }
}
