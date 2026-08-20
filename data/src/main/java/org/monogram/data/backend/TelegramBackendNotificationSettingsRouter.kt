package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoNotificationSettingsRepository
import org.monogram.domain.models.ChatModel
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope

internal class TelegramBackendNotificationSettingsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> NotificationSettingsRepository,
    private val mtProto: MtProtoNotificationSettingsRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : NotificationSettingsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getNotificationSettings(scope: TdNotificationScope) = selected().getNotificationSettings(scope)
    override suspend fun setNotificationSettings(scope: TdNotificationScope, enabled: Boolean) = selected().setNotificationSettings(scope, enabled)
    override suspend fun getExceptions(scope: TdNotificationScope): List<ChatModel> = selected().getExceptions(scope)
    override suspend fun setChatNotificationSettings(chatId: Long, enabled: Boolean) = selected().setChatNotificationSettings(chatId, enabled)
    override suspend fun resetChatNotificationSettings(chatId: Long) = selected().resetChatNotificationSettings(chatId)

    private fun selected(): NotificationSettingsRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
