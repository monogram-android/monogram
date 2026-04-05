package org.monogram.domain.repository

import org.monogram.domain.models.ChatModel

interface NotificationSettingsRepository {
    suspend fun getNotificationSettings(scope: TdNotificationScope): Boolean
    suspend fun setNotificationSettings(scope: TdNotificationScope, enabled: Boolean)

    suspend fun getExceptions(scope: TdNotificationScope): List<ChatModel>
    suspend fun setChatNotificationSettings(chatId: Long, enabled: Boolean)
    suspend fun resetChatNotificationSettings(chatId: Long)

    enum class TdNotificationScope {
        PRIVATE_CHATS, GROUPS, CHANNELS
    }
}