package org.monogram.domain.repository

import org.monogram.domain.models.ChatPermissionsModel

interface ChatSettingsRepository {
    suspend fun setChatPhoto(chatId: Long, photoPath: String)
    suspend fun setChatTitle(chatId: Long, title: String)
    suspend fun setChatDescription(chatId: Long, description: String)
    suspend fun setChatUsername(chatId: Long, username: String)
    suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel)
    suspend fun setChatHasProtectedContent(chatId: Long, hasProtectedContent: Boolean)
    suspend fun setChatSignMessages(chatId: Long, signMessages: Boolean)
    suspend fun setChatHasHiddenMembers(chatId: Long, hasHiddenMembers: Boolean)
    suspend fun setChatHasAggressiveAntiSpamEnabled(chatId: Long, enabled: Boolean)
    suspend fun setChatJoinToSendMessages(chatId: Long, joinToSendMessages: Boolean)
    suspend fun setChatJoinByRequest(chatId: Long, joinByRequest: Boolean)
    suspend fun setChatAvailableReactions(chatId: Long, availableReactions: List<String>)
    suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int)
    suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean)
}