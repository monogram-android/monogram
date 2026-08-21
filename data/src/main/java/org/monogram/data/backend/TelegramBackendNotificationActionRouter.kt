package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.monogram.data.service.NotificationActionManager

/** Prevents stale Android notification actions from constructing TDLib for selected MTProto accounts. */
internal class TelegramBackendNotificationActionRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> NotificationActionManager,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : NotificationActionManager {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override fun consumeNotificationAction(action: String, chatId: Long, notificationId: Int): Boolean =
        selectedBackend.value == TelegramBackendKind.LEGACY &&
            legacy.consumeNotificationAction(action, chatId, notificationId)

    override fun clearHistory(chatId: Long) {
        if (selectedBackend.value == TelegramBackendKind.LEGACY) legacy.clearHistory(chatId)
    }

    override fun removeNotification(chatId: Long, notificationId: Int) {
        if (selectedBackend.value == TelegramBackendKind.LEGACY) legacy.removeNotification(chatId, notificationId)
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
