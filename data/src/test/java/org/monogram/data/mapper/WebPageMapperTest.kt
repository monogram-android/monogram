package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebPageMapperTest {

    @Test
    fun `selectPhotoSizes chooses preferred thumbnail and original sizes`() {
        val sizes = arrayOf(
            photoSize(type = "s", width = 90, height = 90, fileId = 1),
            photoSize(type = "m", width = 320, height = 320, fileId = 2),
            photoSize(type = "x", width = 800, height = 600, fileId = 3),
            photoSize(type = "y", width = 1280, height = 960, fileId = 4)
        )

        val selection = WebPageMapper.selectPhotoSizes(sizes)

        assertEquals(3, selection.preferredSize?.photo?.id)
        assertEquals(2, selection.thumbnailSize?.photo?.id)
        assertEquals(4, selection.originalSize?.photo?.id)
    }

    @Test
    fun `selectPhotoSizes falls back to middle size when x and m are missing`() {
        val sizes = arrayOf(
            photoSize(type = "a", width = 100, height = 100, fileId = 1),
            photoSize(type = "b", width = 300, height = 200, fileId = 2),
            photoSize(type = "c", width = 640, height = 480, fileId = 3)
        )

        val selection = WebPageMapper.selectPhotoSizes(sizes)

        assertEquals(2, selection.preferredSize?.photo?.id)
        assertEquals(1, selection.thumbnailSize?.photo?.id)
        assertEquals(3, selection.originalSize?.photo?.id)
    }

    @Test
    fun `selectPhotoSizes handles empty sizes`() {
        val selection = WebPageMapper.selectPhotoSizes(emptyArray())

        assertNull(selection.preferredSize)
        assertNull(selection.thumbnailSize)
        assertNull(selection.originalSize)
    }

    private fun photoSize(type: String, width: Int, height: Int, fileId: Int): TdApi.PhotoSize {
        return TdApi.PhotoSize().apply {
            this.type = type
            this.width = width
            this.height = height
            this.photo = TdApi.File().apply {
                id = fileId
            }
        }
    }
}
