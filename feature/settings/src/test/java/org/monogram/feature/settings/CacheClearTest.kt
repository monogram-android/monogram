package org.monogram.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.network.http.FileCache

class CacheClearTest {
    private val usage = mapOf(
        FileCache.KIND_PHOTOS to 40L,
        FileCache.KIND_VIDEOS to 0L,
        FileCache.KIND_FILES to 10L,
    )

    @Test
    fun openingTheSheetSelectsOnlyNonEmptyCategoriesAndClearsNothing() {
        val selected = selectableCacheKinds(usage).toSet()
        assertEquals(setOf(FileCache.KIND_PHOTOS, FileCache.KIND_FILES), selected)
        assertTrue(kindsToClear(selected, confirmed = false).isEmpty())
        assertNull(confirmedChatClear(7L, confirmed = false))
    }

    @Test
    fun confirmClearsOnlyTheKindsStillChecked() {
        val opened = selectableCacheKinds(usage).toSet()
        val stillChecked = opened - FileCache.KIND_PHOTOS
        val cleared = mutableListOf<String>()
        kindsToClear(stillChecked, confirmed = true).forEach(cleared::add)
        assertEquals(listOf(FileCache.KIND_FILES), cleared)
        assertEquals(10L, selectedCacheBytes(usage, stillChecked))
    }
}