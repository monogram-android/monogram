package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoAttachMenuBotRepository
import org.monogram.domain.models.AttachMenuBotModel
import org.monogram.domain.repository.AttachMenuBotRepository

internal class TelegramBackendAttachMenuBotRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> AttachMenuBotRepository,
    private val mtProto: MtProtoAttachMenuBotRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : AttachMenuBotRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override fun getAttachMenuBots(): Flow<List<AttachMenuBotModel>> = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getAttachMenuBots()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getAttachMenuBots()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
