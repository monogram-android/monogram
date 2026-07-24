package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatStorageUsageModel
import org.monogram.domain.repository.StringProvider

class StorageMapperTest {

    private val mapper = StorageMapper(FakeStringProvider())

    @Test
    fun `mapToDomain keeps tdlib totals as source of truth`() {
        val statistics = TdApi.StorageStatistics().apply {
            size = 4_096L
            count = 12
        }
        val chatStats = listOf(
            ChatStorageUsageModel(
                chatId = 1L,
                chatTitle = "Chat",
                size = 1_024L,
                fileCount = 3,
                byFileType = emptyList()
            )
        )

        val result = mapper.mapToDomain(statistics, chatStats)

        assertEquals(4_096L, result.totalSize)
        assertEquals(12, result.fileCount)
        assertEquals(chatStats, result.chatStats)
    }

    @Test
    fun `mapChatStatsToDomain keeps manually clearable categories visible`() {
        val statistics = TdApi.StorageStatisticsByChat().apply {
            chatId = 42L
            byFileType = arrayOf(
                fileTypeStat(TdApi.FileTypeSticker(), 100L, 1),
                fileTypeStat(TdApi.FileTypeThumbnail(), 200L, 2),
                fileTypeStat(TdApi.FileTypeProfilePhoto(), 300L, 3),
                fileTypeStat(TdApi.FileTypeWallpaper(), 400L, 4)
            )
        }

        val result = mapper.mapChatStatsToDomain(statistics, "Saved Messages")

        assertEquals(1_000L, result.size)
        assertEquals(10, result.fileCount)
        assertEquals(4, result.byFileType.size)
        assertTrue(result.byFileType.any { it.fileType == "storage_stickers" && it.size == 100L })
        assertEquals(3, result.byFileType.count { it.fileType == "storage_other_files" })
    }

    private fun fileTypeStat(
        fileType: TdApi.FileType,
        size: Long,
        count: Int
    ) = TdApi.StorageStatisticsByFileType().apply {
        this.fileType = fileType
        this.size = size
        this.count = count
    }

    private class FakeStringProvider : StringProvider {
        override fun getString(resName: String): String = resName

        override fun getString(resName: String, vararg formatArgs: Any): String = resName

        override fun getQuantityString(
            resName: String,
            quantity: Int,
            vararg formatArgs: Any
        ): String = resName
    }
}
