package org.monogram.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.cache.ChatLocalDataSource
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.data.datasource.cache.UserLocalDataSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.mapper.StorageMapper
import org.monogram.domain.models.StorageCleanupResultModel
import org.monogram.domain.models.StorageUsageBreakdownModel
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StringProvider

class StorageRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val cache: SettingsCacheDataSource,
    private val chatsRemote: ChatsRemoteDataSource,
    private val dispatchers: DispatcherProvider,
    private val storageMapper: StorageMapper,
    private val stringProvider: StringProvider,
    private val chatLocalDataSource: ChatLocalDataSource,
    private val userLocalDataSource: UserLocalDataSource,
    private val stickerLocalDataSource: StickerLocalDataSource
) : StorageRepository {
    private val manuallyClearableFileTypes = arrayOf(
        TdApi.FileTypeAnimation(),
        TdApi.FileTypeAudio(),
        TdApi.FileTypeDocument(),
        TdApi.FileTypePhoto(),
        TdApi.FileTypePhotoStory(),
        TdApi.FileTypeProfilePhoto(),
        TdApi.FileTypeSticker(),
        TdApi.FileTypeThumbnail(),
        TdApi.FileTypeVideo(),
        TdApi.FileTypeVideoNote(),
        TdApi.FileTypeVideoStory(),
        TdApi.FileTypeVoiceNote(),
        TdApi.FileTypeWallpaper()
    )
    private val backgroundMaintainedFileTypes = arrayOf(
        TdApi.FileTypeAudio(),
        TdApi.FileTypeDocument(),
        TdApi.FileTypePhoto(),
        TdApi.FileTypePhotoStory(),
        TdApi.FileTypeVideo(),
        TdApi.FileTypeVideoNote(),
        TdApi.FileTypeVideoStory(),
        TdApi.FileTypeVoiceNote()
    )

    override suspend fun getStorageUsage(): StorageUsageModel? = coroutineScope {
        val stats = remote.getStorageStatistics(100) ?: return@coroutineScope null
        val processedChats = (stats.byChat ?: emptyArray()).map { chatStat ->
            async(dispatchers.default) {
                val title = when {
                    chatStat.chatId == 0L -> stringProvider.getString("storage_other_cache")
                    else -> cache.getChat(chatStat.chatId)?.title
                        ?: chatsRemote.getChat(chatStat.chatId)?.title
                        ?: stringProvider.getString("storage_chat_format", chatStat.chatId)
                }
                storageMapper.mapChatStatsToDomain(chatStat, title)
            }
        }.awaitAll()

        storageMapper.mapToDomain(stats, processedChats)
    }

    override suspend fun getStorageUsageBreakdown(): StorageUsageBreakdownModel? {
        val stats = remote.getStorageStatisticsFast() ?: return null
        return StorageUsageBreakdownModel(
            tdlibMediaSize = stats.filesSize,
            tdlibDatabaseSize = stats.databaseSize,
            tdlibLogsSize = stats.logSize,
            languagePackDatabaseSize = stats.languagePackDatabaseSize
        )
    }

    override suspend fun clearStorage(chatId: Long?): StorageCleanupResultModel {
        val deletedStats = remote.optimizeStorage(
            size = 0,
            ttl = 0,
            count = 0,
            immunityDelay = 0,
            fileTypes = manuallyClearableFileTypes,
            chatIds = chatId?.let { longArrayOf(it) },
            returnDeletedFileStatistics = true,
            chatLimit = 20
        )
        if (deletedStats != null) {
            clearSessionLocalStorageReferences()
        }
        return StorageCleanupResultModel(
            tdlibFreedSize = deletedStats?.size ?: 0L,
            tdlibFreedFileCount = deletedStats?.count ?: 0,
            tdlibCleanupSucceeded = deletedStats != null
        )
    }

    override suspend fun setDatabaseMaintenanceSettings(
        maxDatabaseSize: Long,
        maxTimeFromLastAccess: Int
    ): Boolean {
        return remote.optimizeStorage(
            size = maxDatabaseSize,
            ttl = maxTimeFromLastAccess,
            count = -1,
            immunityDelay = -1,
            fileTypes = backgroundMaintainedFileTypes,
            chatIds = null,
            returnDeletedFileStatistics = true,
            chatLimit = 0
        ) != null
    }

    override suspend fun getStorageOptimizerEnabled(): Boolean {
        val result = remote.getOption("use_storage_optimizer")
        return if (result is TdApi.OptionValueBoolean) {
            result.value
        } else {
            false
        }
    }

    override suspend fun setStorageOptimizerEnabled(enabled: Boolean) {
        remote.setOption("use_storage_optimizer", TdApi.OptionValueBoolean(enabled))
    }

    private suspend fun clearSessionLocalStorageReferences() {
        chatLocalDataSource.clearCachedMediaPaths()
        chatLocalDataSource.clearCachedChatAvatarPaths()
        userLocalDataSource.clearCachedAvatarPaths()
        stickerLocalDataSource.clearPaths()
    }
}
