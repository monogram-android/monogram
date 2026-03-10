package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.*

interface SettingsRepository {
    suspend fun getActiveSessions(): List<SessionModel>
    suspend fun terminateSession(sessionId: Long): Boolean
    suspend fun confirmQrCode(link: String): Boolean
    fun getWallpapers(): Flow<List<WallpaperModel>>
    suspend fun downloadWallpaper(fileId: Int)

    suspend fun getStorageUsage(): StorageUsageModel?
    suspend fun getNetworkUsage(): NetworkUsageModel?
    suspend fun clearStorage(chatId: Long? = null): Boolean
    suspend fun resetNetworkStatistics(): Boolean
    suspend fun setDatabaseMaintenanceSettings(maxDatabaseSize: Long, maxTimeFromLastAccess: Int): Boolean

    suspend fun getNetworkStatisticsEnabled(): Boolean
    suspend fun setNetworkStatisticsEnabled(enabled: Boolean)

    suspend fun getStorageOptimizerEnabled(): Boolean
    suspend fun setStorageOptimizerEnabled(enabled: Boolean)

    val autoDownloadMobile: StateFlow<Boolean>
    val autoDownloadWifi: StateFlow<Boolean>
    val autoDownloadRoaming: StateFlow<Boolean>
    val autoDownloadFiles: StateFlow<Boolean>
    val autoDownloadStickers: StateFlow<Boolean>
    val autoDownloadVideoNotes: StateFlow<Boolean>

    fun setAutoDownloadMobile(enabled: Boolean)
    fun setAutoDownloadWifi(enabled: Boolean)
    fun setAutoDownloadRoaming(enabled: Boolean)
    fun setAutoDownloadFiles(enabled: Boolean)
    fun setAutoDownloadStickers(enabled: Boolean)
    fun setAutoDownloadVideoNotes(enabled: Boolean)

    suspend fun getNotificationSettings(scope: TdNotificationScope): Boolean
    suspend fun setNotificationSettings(scope: TdNotificationScope, enabled: Boolean)

    suspend fun getExceptions(scope: TdNotificationScope): List<ChatModel>
    suspend fun setChatNotificationSettings(chatId: Long, enabled: Boolean)
    suspend fun resetChatNotificationSettings(chatId: Long)

    fun getAttachMenuBots(): Flow<List<AttachMenuBotModel>>

    suspend fun setCachedSimCountryIso(iso: String?)

    enum class TdNotificationScope {
        PRIVATE_CHATS, GROUPS, CHANNELS
    }
}
