package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoChatSettingsRepository
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.repository.ChatSettingsRepository

internal class TelegramBackendChatSettingsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatSettingsRepository,
    scope: CoroutineScope,
    private val mtProtoFactory: () -> MtProtoChatSettingsRepository = { throw UnsupportedOperationException("MTProto chat settings are not configured") },
    private val accountId: String = "default",
) : ChatSettingsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)
    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }
    override suspend fun setChatPhoto(chatId: Long, photoPath: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatPhoto(chatId, photoPath)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setPhoto(chatId, photoPath)
    }
    override suspend fun setChatTitle(chatId: Long, title: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatTitle(chatId, title)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setTitle(chatId, title)
    }
    override suspend fun setChatDescription(chatId: Long, description: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatDescription(chatId, description)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setDescription(chatId, description)
    }
    override suspend fun setChatUsername(chatId: Long, username: String) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatUsername(chatId, username)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setUsername(chatId, username)
    }
    override suspend fun setChatPermissions(chatId: Long, permissions: ChatPermissionsModel) = call { legacy.setChatPermissions(chatId, permissions) }
    override suspend fun setChatHasProtectedContent(chatId: Long, hasProtectedContent: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatHasProtectedContent(chatId, hasProtectedContent)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setProtectedContent(chatId, hasProtectedContent)
    }
    override suspend fun setChatSignMessages(chatId: Long, signMessages: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatSignMessages(chatId, signMessages)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setSignMessages(chatId, signMessages)
    }
    override suspend fun setChatHasHiddenMembers(chatId: Long, hasHiddenMembers: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatHasHiddenMembers(chatId, hasHiddenMembers)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setParticipantsHidden(chatId, hasHiddenMembers)
    }
    override suspend fun setChatHasAggressiveAntiSpamEnabled(chatId: Long, enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatHasAggressiveAntiSpamEnabled(chatId, enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setAntiSpamEnabled(chatId, enabled)
    }
    override suspend fun setChatJoinToSendMessages(chatId: Long, joinToSendMessages: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatJoinToSendMessages(chatId, joinToSendMessages)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setJoinToSend(chatId, joinToSendMessages)
    }
    override suspend fun setChatJoinByRequest(chatId: Long, joinByRequest: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatJoinByRequest(chatId, joinByRequest)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setJoinByRequest(chatId, joinByRequest)
    }
    override suspend fun setChatAvailableReactions(chatId: Long, availableReactions: List<String>) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatAvailableReactions(chatId, availableReactions)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setAvailableReactions(chatId, availableReactions)
    }
    override suspend fun setChatSlowModeDelay(chatId: Long, slowModeDelay: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setChatSlowModeDelay(chatId, slowModeDelay)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setSlowModeDelay(chatId, slowModeDelay)
    }
    override suspend fun toggleChatIsForum(chatId: Long, isForum: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.toggleChatIsForum(chatId, isForum)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setForumEnabled(chatId, isForum)
    }
    private suspend fun call(action: suspend () -> Unit) { when (selected()) { TelegramBackendKind.LEGACY -> action(); TelegramBackendKind.KOTLIN_MTPROTO -> unsupported() } }
    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto chat settings are not available")
}
