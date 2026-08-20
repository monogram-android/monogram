package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatRevenueStatisticsModel
import org.monogram.domain.models.ChatStatisticsModel
import org.monogram.domain.models.StatisticsGraphModel
import org.monogram.domain.repository.ChatStatisticsRepository

internal class TelegramBackendChatStatisticsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> ChatStatisticsRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ChatStatisticsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }

    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): ChatStatisticsModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getChatStatistics(chatId, isDark)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getChatRevenueStatistics(chatId: Long, isDark: Boolean): ChatRevenueStatisticsModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getChatRevenueStatistics(chatId, isDark)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun loadStatisticsGraph(chatId: Long, token: String, x: Long): StatisticsGraphModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.loadStatisticsGraph(chatId, token, x)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto chat statistics are not available")
    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
