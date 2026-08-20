package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.BotCommandModel
import org.monogram.domain.models.BotInfoModel
import org.monogram.domain.repository.BotRepository

internal class TelegramBackendBotRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> BotRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : BotRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }

    override suspend fun getBotCommands(botId: Long): List<BotCommandModel> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getBotCommands(botId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getBotInfo(botId: Long): BotInfoModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getBotInfo(botId)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto bot operations are not available")
    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
