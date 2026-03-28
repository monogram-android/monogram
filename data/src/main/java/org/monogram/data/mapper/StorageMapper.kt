package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ChatStorageUsageModel
import org.monogram.domain.models.FileTypeStorageUsageModel
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StringProvider

class StorageMapper(private val stringProvider: StringProvider) {
    fun mapToDomain(_statistics: TdApi.StorageStatistics, chatStats: List<ChatStorageUsageModel>): StorageUsageModel {
        val filteredChats = chatStats.filter { it.size > 0L }
        return StorageUsageModel(
            totalSize = filteredChats.sumOf { it.size },
            fileCount = filteredChats.sumOf { it.fileCount },
            chatStats = filteredChats
        )
    }

    fun mapChatStatsToDomain(stats: TdApi.StorageStatisticsByChat, chatTitle: String): ChatStorageUsageModel {
        val removableStats = stats.byFileType.filterNot { isNonRemovableFileType(it.fileType) }
        val mappedFileTypes = removableStats.map { mapFileTypeStatsToDomain(it) }
        return ChatStorageUsageModel(
            chatId = stats.chatId,
            chatTitle = chatTitle,
            size = mappedFileTypes.sumOf { it.size },
            fileCount = mappedFileTypes.sumOf { it.fileCount },
            byFileType = mappedFileTypes
        )
    }

    fun mapFileTypeStatsToDomain(stats: TdApi.StorageStatisticsByFileType): FileTypeStorageUsageModel {
        return FileTypeStorageUsageModel(
            fileType = mapFileTypeToDomain(stats.fileType),
            size = stats.size,
            fileCount = stats.count
        )
    }

    private fun isNonRemovableFileType(fileType: TdApi.FileType): Boolean {
        return when (fileType) {
            is TdApi.FileTypeSticker,
            is TdApi.FileTypeThumbnail,
            is TdApi.FileTypeProfilePhoto,
            is TdApi.FileTypeWallpaper -> true

            else -> false
        }
    }

    fun mapFileTypeToDomain(fileType: TdApi.FileType): String {
        return when (fileType) {
            is TdApi.FileTypePhoto -> stringProvider.getString("storage_photos")
            is TdApi.FileTypeVideo -> stringProvider.getString("storage_videos")
            is TdApi.FileTypeDocument -> stringProvider.getString("storage_documents")
            is TdApi.FileTypeSticker -> stringProvider.getString("storage_stickers")
            is TdApi.FileTypeAudio -> stringProvider.getString("storage_music")
            is TdApi.FileTypeVoiceNote -> stringProvider.getString("storage_voice_messages")
            is TdApi.FileTypeVideoNote -> stringProvider.getString("storage_video_messages")
            else -> stringProvider.getString("storage_other_files")
        }
    }
}
