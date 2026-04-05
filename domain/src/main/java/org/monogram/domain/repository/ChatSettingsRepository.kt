package org.monogram.domain.repository

import org.monogram.domain.models.ChatPermissionsModel

interface ChatSettingsRepository {
    suspend fun setChatPhoto(chatId: Long, photoPath: String)
    suspend fun setChatTitle(chatId: Long, title: String)
    suspend fun setChatDescription(chatId: Long, description: String)
    suspend fun setChatUsername(chatId: Long, username: String)
    suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel)
    suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int)
    suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean)
    suspend fun toggleChatIsTranslatable(chatId: Long, isTranslatable: Boolean)
}