package org.monogram.feature.chats

data class FolderListScroll(
    val index: Int = 0,
    val offset: Int = 0,
)

data class FolderChipClick(
    val selectedId: Int?,
    val saved: Map<Int, FolderListScroll>,
    val scrollToTop: Boolean,
)

fun folderScrollKey(folderId: Int?): Int = folderId ?: Int.MIN_VALUE

/**
 * A saved folder position is only as good as the list it points into: a filter can shrink when
 * dialogs change, and a stale index would scroll into a row that no longer exists.
 */
fun clampFolderScroll(saved: FolderListScroll?, size: Int): FolderListScroll {
    val maxIndex = (size - 1).coerceAtLeast(0)
    val index = (saved?.index ?: 0).coerceIn(0, maxIndex)
    val offset = if (saved == null || index != saved.index) 0 else saved.offset.coerceAtLeast(0)
    return FolderListScroll(index = index, offset = offset)
}

fun onFolderChipClick(
    selectedId: Int?,
    tappedId: Int?,
    currentScroll: FolderListScroll,
    saved: Map<Int, FolderListScroll>,
): FolderChipClick {
    val selectedKey = folderScrollKey(selectedId)
    return if (tappedId == selectedId) {
        FolderChipClick(
            selectedId = selectedId,
            saved = saved + (selectedKey to FolderListScroll()),
            scrollToTop = true,
        )
    } else {
        FolderChipClick(
            selectedId = tappedId,
            saved = saved + (selectedKey to currentScroll),
            scrollToTop = false,
        )
    }
}
