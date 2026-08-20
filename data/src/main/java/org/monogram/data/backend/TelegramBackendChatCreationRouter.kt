package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChatCreationRepository

internal class TelegramBackendChatCreationRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatCreationRepository,
    scope: CoroutineScope,
    private val accountId: String = "default",
) : ChatCreationRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }
    override suspend fun createGroup(title: String, userIds: List<Long>, messageAutoDeleteTime: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.createGroup(title, userIds, messageAutoDeleteTime)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }
    override suspend fun createChannel(title: String, description: String, isMegagroup: Boolean, messageAutoDeleteTime: Int) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.createChannel(title, description, isMegagroup, messageAutoDeleteTime)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }
    override fun getDatabaseSize() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getDatabaseSize()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }
    override fun clearDatabase() = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.clearDatabase()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }
    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto chat creation is not available")
}
