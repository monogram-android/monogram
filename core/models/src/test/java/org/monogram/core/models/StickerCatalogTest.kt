package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerCatalogTest {
    @Test
    fun packKeepsAccessHashForSetFetch() {
        val pack = StickerPack(
            id = 9L,
            title = "Cats",
            shortName = "cats",
            count = 3,
            isEmoji = false,
            previewDocumentIds = listOf(42L),
            accessHash = 11L,
        )
        assertEquals(11L, pack.accessHash)
        assertEquals(listOf(42L), pack.previewDocumentIds)
    }

    @Test
    fun catalogNotModifiedIsEmpty() {
        val catalog = StickerCatalog(hash = 7L, notModified = true)
        assertTrue(catalog.notModified)
        assertTrue(catalog.sets.isEmpty())
        val list = StickerList(hash = 1L, notModified = false, documentIds = listOf(5L))
        assertFalse(list.notModified)
        assertEquals(1, list.documentIds.size)
    }
}
