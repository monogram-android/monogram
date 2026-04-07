package org.monogram.data.datasource.remote

import org.drinkless.tdlib.TdApi

interface SettingsRemoteDataSource {
    // Getters
    suspend fun getScopeNotificationSettings(scope: TdApi.NotificationSettingsScope): TdApi.ScopeNotificationSettings?
    suspend fun getActiveSessions(): TdApi.Sessions?
    suspend fun getInstalledBackgrounds(forDarkTheme: Boolean): TdApi.Backgrounds?
    suspend fun getStorageStatistics(chatLimit: Int): TdApi.StorageStatistics?
    suspend fun getNetworkStatistics(): TdApi.NetworkStatistics?
    suspend fun getOption(name: String): TdApi.OptionValue?
    suspend fun getChatNotificationSettingsExceptions(
        scope: TdApi.NotificationSettingsScope,
        compareSound: Boolean
    ): TdApi.Chats?
    suspend fun setDefaultBackground(
        background: TdApi.InputBackground?,
        type: TdApi.BackgroundType?,
        forDarkTheme: Boolean
    ): TdApi.Background?

    // Setters
    suspend fun setScopeNotificationSettings(
        scope: TdApi.NotificationSettingsScope,
        settings: TdApi.ScopeNotificationSettings
    )
    suspend fun setChatNotificationSettings(chatId: Long, settings: TdApi.ChatNotificationSettings)
    suspend fun setOption(name: String, value: TdApi.OptionValue)

    // Sessions
    suspend fun terminateSession(sessionId: Long): Boolean
    suspend fun confirmQrCode(link: String): Boolean

    // Storage
    suspend fun optimizeStorage(
        size: Long,
        ttl: Int,
        count: Int,
        immunityDelay: Int,
        chatIds: LongArray?,
        returnDeletedFileStatistics: Boolean,
        chatLimit: Int
    ): Boolean
    suspend fun resetNetworkStatistics(): Boolean

    // Files
    suspend fun downloadFile(fileId: Int, priority: Int)
}