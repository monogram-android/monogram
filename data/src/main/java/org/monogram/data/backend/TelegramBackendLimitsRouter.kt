package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.TdLibLimits
import org.monogram.domain.repository.TdLibLimitsRepository

/** Uses protocol-neutral defaults until MTProto exposes account limit metadata. */
internal class TelegramBackendLimitsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> TdLibLimitsRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : TdLibLimitsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProtoLimits = MutableStateFlow(TdLibLimits.DEFAULTS)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override val limits: StateFlow<TdLibLimits>
        get() = when (selected()) {
            TelegramBackendKind.LEGACY -> legacy.limits
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoLimits
        }

    override suspend fun refresh() {
        if (selected() == TelegramBackendKind.LEGACY) legacy.refresh()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
