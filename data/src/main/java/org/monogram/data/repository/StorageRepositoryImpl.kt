package org.monogram.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.mapper.StorageMapper
import org.monogram.domain.models.StorageUsageModel
import org.monogram.domain.repository.StorageRepository
import org.monogram.domain.repository.StringProvider

class StorageRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val cache: SettingsCacheDataSource,
    private val chatsRemote: ChatsRemoteDataSource,
    private val dispatchers: DispatcherProvider,
    private val storageMapper: StorageMapper,
    private val stringProvider: StringProvider
) : StorageRepository {

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

    override suspend fun clearStorage(chatId: Long?): Boolean {
        return remote.optimizeStorage(
            size = 0,
            ttl = 0,
            count = 0,
            immunityDelay = 0,
            chatIds = chatId?.let { longArrayOf(it) },
            returnDeletedFileStatistics = false,
            chatLimit = 20
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
            chatIds = null,
            returnDeletedFileStatistics = true,
            chatLimit = 0
        )
    }

    override suspend fun getStorageOptimizerEnabled(): Boolean {
        val result = remote.getOption("use_storage_optimizer")
        return if (result is org.drinkless.tdlib.TdApi.OptionValueBoolean) {
            result.value
        } else {
            false
        }
    }

    override suspend fun setStorageOptimizerEnabled(enabled: Boolean) {
        remote.setOption("use_storage_optimizer", org.drinkless.tdlib.TdApi.OptionValueBoolean(enabled))
    }
}