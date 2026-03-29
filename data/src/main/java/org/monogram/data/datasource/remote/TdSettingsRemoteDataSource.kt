package org.monogram.data.datasource.remote

import org.monogram.data.core.coRunCatching
import android.util.Log
import org.drinkless.tdlib.TdApi
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.infra.FileDownloadQueue

class TdSettingsRemoteDataSource(
    private val gateway: TelegramGateway,
    private val fileQueue: FileDownloadQueue
) : SettingsRemoteDataSource {

    override suspend fun getScopeNotificationSettings(
        scope: TdApi.NotificationSettingsScope
    ): TdApi.ScopeNotificationSettings? =
        coRunCatching { gateway.execute(TdApi.GetScopeNotificationSettings(scope)) }.getOrNull()

    override suspend fun getActiveSessions(): TdApi.Sessions? =
        coRunCatching { gateway.execute(TdApi.GetActiveSessions()) }.getOrNull()

    override suspend fun getInstalledBackgrounds(forDarkTheme: Boolean): TdApi.Backgrounds? =
        coRunCatching {
            val result = gateway.execute(TdApi.GetInstalledBackgrounds(forDarkTheme))
            result.backgrounds.forEach { bg ->
                bg.document?.thumbnail?.file?.let { file ->
                    if (file.local.path.isEmpty()) {
                        fileQueue.enqueue(file.id, 1, FileDownloadQueue.DownloadType.DEFAULT)
                    }
                }
            }
            result
        }.getOrNull()

    override suspend fun getStorageStatistics(chatLimit: Int): TdApi.StorageStatistics? =
        coRunCatching { gateway.execute(TdApi.GetStorageStatistics(chatLimit)) }.getOrNull()

    override suspend fun getNetworkStatistics(): TdApi.NetworkStatistics? =
        coRunCatching {
            Log.d("NetworkStats", "Fetching network statistics...")
            val stats = gateway.execute(TdApi.GetNetworkStatistics(true))
            Log.d("NetworkStats", "Received stats with ${stats.entries.size} entries")
            stats.entries.forEachIndexed { index, entry ->
                when (entry) {
                    is TdApi.NetworkStatisticsEntryFile -> {
                        Log.d(
                            "NetworkStats",
                            "Entry $index: File - Type: ${entry.fileType}, Network: ${entry.networkType}, Sent: ${entry.sentBytes}, Received: ${entry.receivedBytes}"
                        )
                    }

                    is TdApi.NetworkStatisticsEntryCall -> {
                        Log.d(
                            "NetworkStats",
                            "Entry $index: Call - Network: ${entry.networkType}, Sent: ${entry.sentBytes}, Received: ${entry.receivedBytes}, Duration: ${entry.duration}"
                        )
                    }
                }
            }
            stats
        }.onFailure {
            Log.e("NetworkStats", "Failed to fetch network statistics", it)
        }.getOrNull()

    override suspend fun getOption(name: String): TdApi.OptionValue? =
        coRunCatching { gateway.execute(TdApi.GetOption(name)) }.getOrNull()

    override suspend fun getChatNotificationSettingsExceptions(
        scope: TdApi.NotificationSettingsScope,
        compareSound: Boolean
    ): TdApi.Chats? =
        coRunCatching {
            gateway.execute(TdApi.GetChatNotificationSettingsExceptions(scope, compareSound))
        }.getOrNull()

    override suspend fun setScopeNotificationSettings(
        scope: TdApi.NotificationSettingsScope,
        settings: TdApi.ScopeNotificationSettings
    ) {
        coRunCatching { gateway.execute(TdApi.SetScopeNotificationSettings(scope, settings)) }
    }

    override suspend fun setChatNotificationSettings(
        chatId: Long,
        settings: TdApi.ChatNotificationSettings
    ) {
        coRunCatching { gateway.execute(TdApi.SetChatNotificationSettings(chatId, settings)) }
    }

    override suspend fun setOption(name: String, value: TdApi.OptionValue) {
        coRunCatching { gateway.execute(TdApi.SetOption(name, value)) }
    }

    override suspend fun terminateSession(sessionId: Long): Boolean =
        coRunCatching { gateway.execute(TdApi.TerminateSession(sessionId)); true }.getOrDefault(false)

    override suspend fun confirmQrCode(link: String): Boolean =
        coRunCatching { gateway.execute(TdApi.ConfirmQrCodeAuthentication(link)); true }.getOrDefault(false)

    override suspend fun optimizeStorage(
        size: Long,
        ttl: Int,
        count: Int,
        immunityDelay: Int,
        chatIds: LongArray?,
        returnDeletedFileStatistics: Boolean,
        chatLimit: Int
    ): Boolean =
        coRunCatching {
            gateway.execute(
                TdApi.OptimizeStorage(size, ttl, count, immunityDelay, null, chatIds, null, returnDeletedFileStatistics, chatLimit)
            )
            true
        }.getOrDefault(false)

    override suspend fun resetNetworkStatistics(): Boolean =
        coRunCatching {
            Log.d("NetworkStats", "Resetting network statistics...")
            gateway.execute(TdApi.ResetNetworkStatistics())
            true
        }.getOrDefault(false)

    override suspend fun downloadFile(fileId: Int, priority: Int) {
        fileQueue.enqueue(fileId, priority, FileDownloadQueue.DownloadType.DEFAULT)
    }
}