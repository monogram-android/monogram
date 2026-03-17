package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.monogram.domain.models.ChatStorageUsageModel
import org.monogram.domain.models.FileTypeStorageUsageModel
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StringProvider

class StorageMapper(private val stringProvider: StringProvider) {
    fun mapToDomain(statistics: TdApi.StorageStatistics, chatStats: List<ChatStorageUsageModel>): StorageUsageModel {
        return StorageUsageModel(
            totalSize = statistics.size,
            fileCount = statistics.count,
            chatStats = chatStats
        )
    }

    fun mapChatStatsToDomain(stats: TdApi.StorageStatisticsByChat, chatTitle: String): ChatStorageUsageModel {
        return ChatStorageUsageModel(
            chatId = stats.chatId,
            chatTitle = chatTitle,
            size = stats.size,
            fileCount = stats.count,
            byFileType = stats.byFileType.map { mapFileTypeStatsToDomain(it) }
        )
    }

    fun mapFileTypeStatsToDomain(stats: TdApi.StorageStatisticsByFileType): FileTypeStorageUsageModel {
        return FileTypeStorageUsageModel(
            fileType = mapFileTypeToDomain(stats.fileType),
            size = stats.size,
            fileCount = stats.count
        )
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
