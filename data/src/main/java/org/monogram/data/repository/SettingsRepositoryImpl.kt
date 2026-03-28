package org.monogram.data.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.db.dao.AttachBotDao
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.dao.WallpaperDao
import org.monogram.data.db.model.AttachBotEntity
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.data.db.model.WallpaperEntity
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.*
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.*
import org.monogram.domain.repository.AppPreferencesProvider
import org.monogram.domain.repository.CacheProvider
import org.monogram.domain.repository.SettingsRepository
import org.monogram.domain.repository.SettingsRepository.TdNotificationScope
import org.monogram.domain.repository.StringProvider


class SettingsRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val cache: SettingsCacheDataSource,
    private val chatsRemote: ChatsRemoteDataSource,
    private val updates: UpdateDispatcher,
    private val appPreferences: AppPreferencesProvider,
    private val cacheProvider: CacheProvider,
    scopeProvider: ScopeProvider,
    private val dispatchers: DispatcherProvider,
    private val attachBotDao: AttachBotDao,
    private val keyValueDao: KeyValueDao,
    private val wallpaperDao: WallpaperDao,
    private val storageMapper: StorageMapper,
    private val stringProvider: StringProvider,
    private val networkMapper: NetworkMapper
) : SettingsRepository {

    private val scope = scopeProvider.appScope

    private val _wallpaperUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _attachMenuBots = MutableStateFlow<List<AttachMenuBotModel>>(cacheProvider.attachBots.value)
    private val _wallpapers = MutableStateFlow<List<WallpaperModel>>(emptyList())

    override val autoDownloadMobile = appPreferences.autoDownloadMobile
    override val autoDownloadWifi = appPreferences.autoDownloadWifi
    override val autoDownloadRoaming = appPreferences.autoDownloadRoaming
    override val autoDownloadFiles = appPreferences.autoDownloadFiles
    override val autoDownloadStickers = appPreferences.autoDownloadStickers
    override val autoDownloadVideoNotes = appPreferences.autoDownloadVideoNotes

    init {
        scope.launch {
            updates.newChat.collect { update -> cache.putChat(update.chat) }
        }
        scope.launch {
            updates.chatTitle.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) { chat.title = update.title }
                }
            }
        }
        scope.launch {
            updates.chatPhoto.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) { chat.photo = update.photo }
                }
            }
        }
        scope.launch {
            updates.chatNotificationSettings.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) { chat.notificationSettings = update.notificationSettings }
                }
            }
        }
        scope.launch {
            updates.attachmentMenuBots.collect { update ->
                cache.putAttachMenuBots(update.bots)
                val bots = update.bots.map { it.toDomain() }
                _attachMenuBots.value = bots
                cacheProvider.setAttachBots(bots)

                saveAttachBotsToDb(bots)

                update.bots.forEach { bot ->
                    bot.androidSideMenuIcon?.let { icon ->
                        if (icon.local.path.isEmpty()) {
                            remote.downloadFile(icon.id, 1)
                        }
                    }
                }
            }
        }
        scope.launch {
            updates.file.collect { update ->
                _wallpaperUpdates.emit(Unit)

                val currentBots = _attachMenuBots.value
                if (currentBots.any { it.icon?.icon?.id == update.file.id }) {
                    cache.getAttachMenuBots()?.let { bots ->
                        val domainBots = bots.map { it.toDomain() }
                        _attachMenuBots.value = domainBots
                        cacheProvider.setAttachBots(domainBots)
                        saveAttachBotsToDb(domainBots)
                    }
                }
            }
        }

        scope.launch {
            attachBotDao.getAttachBots().collect { entities ->
                val bots = entities.mapNotNull {
                    try {
                        Json.decodeFromString<AttachMenuBotModel>(it.data)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bots.isNotEmpty()) {
                    _attachMenuBots.value = bots
                    cacheProvider.setAttachBots(bots)
                }
            }
        }

        scope.launch {
            keyValueDao.observeValue("cached_sim_country_iso").collect { entity ->
                cacheProvider.setCachedSimCountryIso(entity?.value)
            }
        }

        scope.launch {
            wallpaperDao.getWallpapers().collect { entities ->
                val wallpapers = entities.mapNotNull {
                    try {
                        Json.decodeFromString<WallpaperModel>(it.data)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (wallpapers.isNotEmpty()) {
                    _wallpapers.value = wallpapers
                }
            }
        }
    }

    private suspend fun saveAttachBotsToDb(bots: List<AttachMenuBotModel>) {
        withContext(dispatchers.io) {
            attachBotDao.clearAll()
            attachBotDao.insertAttachBots(bots.map {
                AttachBotEntity(it.botUserId, Json.encodeToString(it))
            })
        }
    }

    private suspend fun saveWallpapersToDb(wallpapers: List<WallpaperModel>) {
        withContext(dispatchers.io) {
            wallpaperDao.clearAll()
            wallpaperDao.insertWallpapers(wallpapers.map {
                WallpaperEntity(it.id, Json.encodeToString(it))
            })
        }
    }

    override fun setAutoDownloadMobile(enabled: Boolean) = appPreferences.setAutoDownloadMobile(enabled)
    override fun setAutoDownloadWifi(enabled: Boolean) = appPreferences.setAutoDownloadWifi(enabled)
    override fun setAutoDownloadRoaming(enabled: Boolean) = appPreferences.setAutoDownloadRoaming(enabled)
    override fun setAutoDownloadFiles(enabled: Boolean) = appPreferences.setAutoDownloadFiles(enabled)
    override fun setAutoDownloadStickers(enabled: Boolean) = appPreferences.setAutoDownloadStickers(enabled)
    override fun setAutoDownloadVideoNotes(enabled: Boolean) = appPreferences.setAutoDownloadVideoNotes(enabled)

    override suspend fun getNotificationSettings(scope: TdNotificationScope): Boolean {
        val result = remote.getScopeNotificationSettings(scope.toApi())
        return result?.muteFor == 0
    }

    override suspend fun setNotificationSettings(scope: TdNotificationScope, enabled: Boolean) {
        val settings = TdApi.ScopeNotificationSettings().apply {
            muteFor = if (enabled) 0 else Int.MAX_VALUE
            useDefaultMuteStories = false
        }
        remote.setScopeNotificationSettings(scope.toApi(), settings)
    }

    override suspend fun getExceptions(scope: TdNotificationScope): List<ChatModel> = coroutineScope {
        val chats = remote.getChatNotificationSettingsExceptions(scope.toApi(), true)
        chats?.chatIds?.map { chatId ->
            async(dispatchers.io) {
                cache.getChat(chatId)?.toDomain()
                    ?: chatsRemote.getChat(chatId)?.also { cache.putChat(it) }?.toDomain()
            }
        }?.awaitAll()?.filterNotNull() ?: emptyList()
    }

    override suspend fun setChatNotificationSettings(chatId: Long, enabled: Boolean) {
        val settings = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = false
            muteFor = if (enabled) 0 else Int.MAX_VALUE
            useDefaultSound = true
            useDefaultShowPreview = true
            useDefaultMuteStories = true
        }
        remote.setChatNotificationSettings(chatId, settings)
    }

    override suspend fun resetChatNotificationSettings(chatId: Long) {
        val settings = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = true
            useDefaultSound = true
            useDefaultShowPreview = true
            useDefaultMuteStories = true
        }
        remote.setChatNotificationSettings(chatId, settings)
    }

    override fun getAttachMenuBots(): Flow<List<AttachMenuBotModel>> {
        return _attachMenuBots
    }

    override suspend fun setCachedSimCountryIso(iso: String?) {
        withContext(dispatchers.io) {
            if (iso != null) {
                keyValueDao.insertValue(KeyValueEntity("cached_sim_country_iso", iso))
            } else {
                keyValueDao.deleteValue("cached_sim_country_iso")
            }
        }
    }

    override suspend fun getActiveSessions(): List<SessionModel> {
        return remote.getActiveSessions()?.sessions?.map { it.toDomain() } ?: emptyList()
    }

    override suspend fun terminateSession(sessionId: Long): Boolean =
        remote.terminateSession(sessionId)

    override suspend fun confirmQrCode(link: String): Boolean =
        remote.confirmQrCode(link)

    override fun getWallpapers(): Flow<List<WallpaperModel>> = callbackFlow {
        suspend fun fetch() {
            val result = remote.getInstalledBackgrounds(false)
            val wallpapers = mapBackgrounds(result?.backgrounds ?: emptyArray())
            _wallpapers.value = wallpapers
            saveWallpapersToDb(wallpapers)
            trySend(wallpapers)
        }

        val wallpaperJob = _wallpaperUpdates
            .onEach { fetch() }
            .launchIn(this)

        if (_wallpapers.value.isNotEmpty()) {
            trySend(_wallpapers.value)
        } else {
            fetch()
        }
        awaitClose { wallpaperJob.cancel() }
    }

    override suspend fun downloadWallpaper(fileId: Int) {
        remote.downloadFile(fileId, 1)
    }

    override suspend fun getStorageUsage(): StorageUsageModel? = coroutineScope {
        val stats = remote.getStorageStatistics(100) ?: return@coroutineScope null
        val processed_chats = (stats.byChat ?: emptyArray()).map { chatStat ->
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
        storageMapper.mapToDomain(stats, processed_chats)
    }

    override suspend fun getNetworkUsage(): NetworkUsageModel? =
        remote.getNetworkStatistics()?.let { networkMapper.mapToDomain(it) }

    override suspend fun clearStorage(chatId: Long?): Boolean =
        remote.optimizeStorage(
            size = 0,
            ttl = 0,
            count = 0,
            immunityDelay = 0,
            chatIds = chatId?.let { longArrayOf(it) },
            returnDeletedFileStatistics = false,
            chatLimit = 20
        )

    override suspend fun resetNetworkStatistics(): Boolean =
        remote.resetNetworkStatistics()

    override suspend fun setDatabaseMaintenanceSettings(
        maxDatabaseSize: Long,
        maxTimeFromLastAccess: Int
    ): Boolean =
        remote.optimizeStorage(
            size = maxDatabaseSize,
            ttl = maxTimeFromLastAccess,
            count = -1,
            immunityDelay = -1,
            chatIds = null,
            returnDeletedFileStatistics = true,
            chatLimit = 0
        )

    override suspend fun getNetworkStatisticsEnabled(): Boolean {
        val result = remote.getOption("disable_network_statistics")
        return if (result is TdApi.OptionValueBoolean) !result.value else true
    }

    override suspend fun setNetworkStatisticsEnabled(enabled: Boolean) {
        remote.setOption("disable_network_statistics", TdApi.OptionValueBoolean(!enabled))
        remote.setOption("disable_persistent_network_statistics", TdApi.OptionValueBoolean(!enabled))
    }

    override suspend fun getStorageOptimizerEnabled(): Boolean {
        val result = remote.getOption("use_storage_optimizer")
        return if (result is TdApi.OptionValueBoolean) result.value else false
    }

    override suspend fun setStorageOptimizerEnabled(enabled: Boolean) {
        remote.setOption("use_storage_optimizer", TdApi.OptionValueBoolean(enabled))
    }
}
