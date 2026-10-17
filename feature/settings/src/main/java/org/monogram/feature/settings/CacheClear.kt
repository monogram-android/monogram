package org.monogram.feature.settings

import org.monogram.network.http.FileCache

internal val storageKinds = listOf(
    FileCache.KIND_PHOTOS,
    FileCache.KIND_VIDEOS,
    FileCache.KIND_FILES,
    FileCache.KIND_STICKERS,
    FileCache.KIND_GIFS,
    FileCache.KIND_OTHER,
)

/** Categories that have something to clear. Opening this set does not delete anything. */
internal fun selectableCacheKinds(byKind: Map<String, Long>): List<String> =
    storageKinds.filter { (byKind[it] ?: 0L) > 0L }

internal fun selectedCacheBytes(byKind: Map<String, Long>, selected: Set<String>): Long =
    selected.sumOf { kind -> byKind[kind] ?: 0L }

/** Nothing is cleared until the user confirms, and then only the kinds still checked. */
internal fun kindsToClear(selected: Set<String>, confirmed: Boolean): Set<String> =
    if (confirmed) selected else emptySet()

internal fun confirmedChatClear(chatId: Long?, confirmed: Boolean): Long? =
    if (confirmed) chatId else null
