package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.domain.models.PremiumFeaturesModel
import org.monogram.domain.models.PremiumSource
import org.monogram.domain.models.PremiumStateModel
import org.monogram.domain.repository.PremiumRepository

internal class TelegramBackendPremiumRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> PremiumRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : PremiumRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init { scope.launch { selectionStore.observe(accountId).collect { selectedBackend.value = it } } }

    override suspend fun getPremiumState(): PremiumStateModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getPremiumState()
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun getPremiumFeatures(source: PremiumSource): PremiumFeaturesModel? = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getPremiumFeatures(source)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    override suspend fun setSponsoredMessagesEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setSponsoredMessagesEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> unsupported()
    }

    private fun selected() = checkNotNull(selectedBackend.value) { "Telegram backend selection is not loaded" }
    private fun unsupported(): Nothing = throw UnsupportedOperationException("MTProto premium operations are not available")
    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
