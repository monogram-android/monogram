package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.models.Folder
import org.monogram.core.models.PeerId

/** The arrangement only lives in the row order, so the mapper carries the index. */
class FolderOrderCacheTest {
    private val folder = Folder(
        id = 4,
        title = "Work",
        emoticon = "💼",
        chatIds = listOf(PeerId(11), PeerId(12)),
        pinnedChatIds = listOf(PeerId(11)),
        excludeChatIds = listOf(PeerId(13)),
        includeGroups = true,
        excludeMuted = true,
    )

    @Test
    fun listIndexBecomesTheRowPosition() {
        assertEquals(0, folder.toEntity(position = 0).position)
        assertEquals(3, folder.toEntity(position = 3).position)
    }

    @Test
    fun positionDefaultsToFirst() {
        assertEquals(0, folder.toEntity().position)
    }

    @Test
    fun modelRoundTripDropsThePosition() {
        val back = folder.toEntity(position = 2).toModel()
        assertEquals(folder, back)
    }
}
