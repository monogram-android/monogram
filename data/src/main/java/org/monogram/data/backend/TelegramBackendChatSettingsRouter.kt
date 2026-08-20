package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.repository.ChatSettingsRepository

internal class TelegramBackendChatSettingsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatSettingsRepository,
    scope: CoroutineScope,
    private val accountId: String = "default",
) : ChatSettingsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }
    override suspend fun setChatPhoto(chatId: Long, photoPath: String) = call { legacy.setChatPhoto(chatId, photoPath) }
    override suspend fun setChatTitle(chatId: Long, title: String) = call { legacy.setChatTitle(chatId, title) }
    override suspend fun setChatDescription(chatId: Long, description: String) = call { legacy.setChatDescription(chatId, description) }
    override suspend fun setChatUsername(chatId: Long, username: String) = call { legacy.setChatUsername(chatId, username) }
    override suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel) = call { legacy.setChatPermissions(chatId, permissions) }
    override suspend fun setChatHasProtectedContent(chatId: Long, hasProtectedContent: Boolean) = call { legacy.setChatHasProtectedContent(chatId, hasProtectedContent) }
    override suspend fun setChatSignMessages(chatId: Long, signMessages: Boolean) = call { legacy.setChatSignMessages(chatId, signMessages) }
    override suspend fun setChatHasHiddenMembers(chatId: Long, hasHiddenMembers: Boolean) = call { legacy.setChatHasHiddenMembers(chatId, hasHiddenMembers) }
    override suspend fun setChatHasAggressiveAntiSpamEnabled(chatId: Long, enabled: Boolean) = call { legacy.setChatHasAggressiveAntiSpamEnabled(chatId, enabled) }
    override suspend fun setChatJoinToSendMessages(chatId: Long, joinToSendMessages: Boolean) = call { legacy.setChatJoinToSendMessages(chatId, joinToSendMessages) }
    override suspend fun setChatJoinByRequest(chatId: Long, joinByRequest: Boolean) = call { legacy.setChatJoinByRequest(chatId, joinByRequest) }
    override suspend fun setChatAvailableReactions(chatId: Long, availableReactions: List<String>) = call { legacy.setChatAvailableReactions(chatId, availableReactions) }
    override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) = call { legacy.setChatSlowModeDelay(chatId, slowModeDelay) }
    override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) = call { legacy.toggleChatIsForum(chatId, isForum) }
    private suspend fun call(action: suspend () -> Unit) { when (selected()) { TelegramBackendKind.LEGACY -> action(); TelegramBackendKind.KOTLIN_MTPROTO -> unsupported() } }
    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto chat settings are not available")
}
