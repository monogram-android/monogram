package org.monogram.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.db.dao.NotificationExceptionDao
import org.monogram.data.db.model.NotificationExceptionEntity
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.toApi
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatType
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import java.util.concurrent.ConcurrentHashMap

class NotificationSettingsRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val cache: SettingsCacheDataSource,
    private val chatsRemote: ChatsRemoteDataSource,
    private val notificationExceptionDao: NotificationExceptionDao,
    private val updates: UpdateDispatcher,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider
) : NotificationSettingsRepository {

    private val exceptionsCache = ConcurrentHashMap<TdNotificationScope, List<ChatModel>>()
    private val exceptionsCacheMutex = Mutex()

    init {
        scope.launch {
            updates.newChat.collect { update ->
                cache.putChat(update.chat)
                syncChatWithExceptionsCache(update.chat)
            }
        }

        scope.launch {
            updates.chatTitle.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) {
                        chat.title = update.title
                    }
                    syncChatWithExceptionsCache(chat)
                }
            }
        }

        scope.launch {
            updates.chatPhoto.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) {
                        chat.photo = update.photo
                    }
                    syncChatWithExceptionsCache(chat)
                }
            }
        }

        scope.launch {
            updates.chatNotificationSettings.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) {
                        chat.notificationSettings = update.notificationSettings
                    }
                    syncChatWithExceptionsCache(chat)
                } ?: run {
                    if (update.notificationSettings.isException(compareSound = true)) {
                        invalidateExceptionsCache()
                    } else {
                        removeFromExceptionsCache(update.chatId)
                    }
                }
            }
        }
    }

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

    override suspend fun getExceptions(scope: TdNotificationScope): List<ChatModel> {
        exceptionsCache[scope]?.let { return it }

        return exceptionsCacheMutex.withLock {
            exceptionsCache[scope]?.let { return@withLock it }

            loadExceptionsFromRoom(scope)?.let { roomCached ->
                exceptionsCache[scope] = roomCached
                return@withLock roomCached
            }

            val remoteLoaded = loadExceptionsFromApi(scope)
            exceptionsCache[scope] = remoteLoaded
            persistScopeToRoom(scope, remoteLoaded)
            remoteLoaded
        }
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
        updateCachedChatMute(chatId, isMuted = !enabled)
    }

    override suspend fun resetChatNotificationSettings(chatId: Long) {
        val settings = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = true
            useDefaultSound = true
            useDefaultShowPreview = true
            useDefaultMuteStories = true
        }
        remote.setChatNotificationSettings(chatId, settings)
        removeFromExceptionsCache(chatId)
    }

    private suspend fun loadExceptionsFromRoom(scope: TdNotificationScope): List<ChatModel>? =
        withContext(dispatchers.io) {
            val cached = notificationExceptionDao.getByScope(scope.name)
            if (cached.isEmpty()) null else cached.map { it.toDomainChatModel() }
        }

    private suspend fun loadExceptionsFromApi(scope: TdNotificationScope): List<ChatModel> =
        withContext(dispatchers.io) {
            val chats = remote.getChatNotificationSettingsExceptions(scope.toApi(), true)
            val result = mutableListOf<ChatModel>()

            chats?.chatIds?.distinct()?.forEach { chatId ->
                val chat = cache.getChat(chatId)
                    ?: chatsRemote.getChat(chatId)?.also { cache.putChat(it) }

                chat?.toDomain()?.let(result::add)
            }

            result
        }

    private suspend fun persistScopeToRoom(scope: TdNotificationScope, chats: List<ChatModel>) {
        withContext(dispatchers.io) {
            notificationExceptionDao.replaceForScope(
                scope = scope.name,
                entities = chats.map { it.toExceptionEntity(scope) }
            )
        }
    }

    private fun syncChatWithExceptionsCache(chat: TdApi.Chat) {
        val notificationScope = chat.toNotificationScope() ?: return
        val isException = chat.notificationSettings.isException(compareSound = true)
        val mappedChat = if (isException) chat.toDomain() else null

        exceptionsCache[notificationScope]?.let { existing ->
            val updated = if (isException && mappedChat != null) {
                if (existing.any { it.id == chat.id }) {
                    existing.map { cached ->
                        if (cached.id == chat.id) mappedChat else cached
                    }
                } else {
                    existing + mappedChat
                }
            } else {
                existing.filterNot { it.id == chat.id }
            }

            exceptionsCache[notificationScope] = updated
        }

        scope.launch(dispatchers.io) {
            if (isException && mappedChat != null) {
                notificationExceptionDao.insert(mappedChat.toExceptionEntity(notificationScope))
            } else {
                notificationExceptionDao.deleteByChatId(chat.id)
            }
        }
    }

    private fun updateCachedChatMute(chatId: Long, isMuted: Boolean) {
        if (exceptionsCache.isNotEmpty()) {
            exceptionsCache.keys.forEach { notificationScope ->
                val existing = exceptionsCache[notificationScope] ?: return@forEach
                exceptionsCache[notificationScope] = existing.map { chat ->
                    if (chat.id == chatId) chat.copy(isMuted = isMuted) else chat
                }
            }
        }

        scope.launch(dispatchers.io) {
            notificationExceptionDao.updateMute(chatId = chatId, isMuted = isMuted)
        }
    }

    private fun removeFromExceptionsCache(chatId: Long) {
        if (exceptionsCache.isNotEmpty()) {
            exceptionsCache.keys.forEach { notificationScope ->
                val existing = exceptionsCache[notificationScope] ?: return@forEach
                exceptionsCache[notificationScope] = existing.filterNot { it.id == chatId }
            }
        }

        scope.launch(dispatchers.io) {
            notificationExceptionDao.deleteByChatId(chatId)
        }
    }

    private fun invalidateExceptionsCache() {
        exceptionsCache.clear()
        scope.launch(dispatchers.io) {
            notificationExceptionDao.clearAll()
        }
    }

    private fun TdApi.Chat.toNotificationScope(): TdNotificationScope? = when (val chatType = type) {
        is TdApi.ChatTypePrivate -> TdNotificationScope.PRIVATE_CHATS
        is TdApi.ChatTypeBasicGroup -> TdNotificationScope.GROUPS
        is TdApi.ChatTypeSupergroup -> if (chatType.isChannel) TdNotificationScope.CHANNELS else TdNotificationScope.GROUPS
        else -> null
    }

    private fun TdApi.ChatNotificationSettings.isException(compareSound: Boolean): Boolean {
        if (!useDefaultMuteFor) return true
        if (!useDefaultShowPreview) return true
        if (!useDefaultMuteStories) return true
        if (!useDefaultShowStoryPoster) return true
        if (!useDefaultDisablePinnedMessageNotifications) return true
        if (!useDefaultDisableMentionNotifications) return true

        if (compareSound) {
            if (!useDefaultSound) return true
            if (!useDefaultStorySound) return true
        }

        return false
    }

    private fun ChatModel.toExceptionEntity(scope: TdNotificationScope): NotificationExceptionEntity {
        return NotificationExceptionEntity(
            chatId = id,
            scope = scope.name,
            title = title,
            avatarPath = avatarPath,
            personalAvatarPath = personalAvatarPath,
            isMuted = isMuted,
            isGroup = isGroup,
            isChannel = isChannel,
            type = type.name
        )
    }

    private fun NotificationExceptionEntity.toDomainChatModel(): ChatModel {
        return ChatModel(
            id = chatId,
            title = title,
            unreadCount = 0,
            avatarPath = avatarPath,
            personalAvatarPath = personalAvatarPath,
            isMuted = isMuted,
            isGroup = isGroup,
            isChannel = isChannel,
            type = type.toDomainChatType()
        )
    }

    private fun String.toDomainChatType(): ChatType {
        return runCatching { ChatType.valueOf(this) }
            .getOrDefault(ChatType.PRIVATE)
    }
}