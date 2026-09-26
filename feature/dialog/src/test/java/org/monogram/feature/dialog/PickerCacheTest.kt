package org.monogram.feature.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.core.models.SavedGif
import org.monogram.core.models.StickerPack

class PickerCacheTest {
    @Test
    fun catalogRoundtripKeepsHashAndPreviewIds() {
        val pack = StickerPack(
            id = 9L,
            title = "Cats",
            shortName = "cats",
            count = 3,
            isEmoji = false,
            previewDocumentIds = listOf(42L, 43L),
            accessHash = 11L,
        )
        val raw = PickerDisk.encodeCatalog(StickerCatalogSnapshot(99L, listOf(pack)))
        val decoded = PickerDisk.decodeCatalog(raw)
        assertEquals(99L, decoded.hash)
        assertEquals(9L, decoded.sets.single().id)
        assertEquals(11L, decoded.sets.single().accessHash)
        assertEquals(listOf(42L, 43L), decoded.sets.single().previewDocumentIds)
    }

    @Test
    fun gifsRoundtripKeepsThumbKeys() {
        val raw = PickerDisk.encodeGifs(
            listOf(
                SavedGif(documentId = 11L, cacheKey = "doc:11", thumbCacheKey = "doc:11:thumb"),
                SavedGif(documentId = 12L, cacheKey = "gif:12"),
            ),
        )
        val decoded = PickerDisk.decodeGifs(raw)
        assertEquals(11L, decoded[0].documentId)
        assertEquals("doc:11:thumb", decoded[0].thumbCacheKey)
        assertEquals(12L, decoded[1].documentId)
        assertNull(decoded[1].thumbCacheKey)
    }
}
