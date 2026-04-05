package org.monogram.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import org.monogram.core.DispatcherProvider
import org.monogram.core.ScopeProvider
import org.monogram.data.datasource.cache.SettingsCacheDataSource
import org.monogram.data.datasource.remote.ChatsRemoteDataSource
import org.monogram.data.datasource.remote.SettingsRemoteDataSource
import org.monogram.data.gateway.UpdateDispatcher
import org.monogram.data.mapper.toApi
import org.monogram.data.mapper.user.toDomain
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope

class NotificationSettingsRepositoryImpl(
    private val remote: SettingsRemoteDataSource,
    private val cache: SettingsCacheDataSource,
    private val chatsRemote: ChatsRemoteDataSource,
    private val updates: UpdateDispatcher,
    scopeProvider: ScopeProvider,
    private val dispatchers: DispatcherProvider
) : NotificationSettingsRepository {

    private val scope = scopeProvider.appScope

    init {
        scope.launch {
            updates.newChat.collect { update ->
                cache.putChat(update.chat)
            }
        }

        scope.launch {
            updates.chatTitle.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) {
                        chat.title = update.title
                    }
                }
            }
        }

        scope.launch {
            updates.chatPhoto.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) {
                        chat.photo = update.photo
                    }
                }
            }
        }

        scope.launch {
            updates.chatNotificationSettings.collect { update ->
                cache.getChat(update.chatId)?.let { chat ->
                    synchronized(chat) {
                        chat.notificationSettings = update.notificationSettings
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
}