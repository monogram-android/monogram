package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoNetworkStatisticsRepository
import org.monogram.domain.models.NetworkUsageModel
import org.monogram.domain.repository.NetworkStatisticsRepository

/** Keeps TDLib-local network statistics out of selected MTProto accounts. */
internal class TelegramBackendNetworkStatisticsRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> NetworkStatisticsRepository,
    private val mtProtoFactory: () -> MtProtoNetworkStatisticsRepository = {
        throw UnsupportedOperationException("MTProto network statistics are not configured")
    },
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : NetworkStatisticsRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)
    private val mtProto by lazy(LazyThreadSafetyMode.NONE, mtProtoFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getNetworkUsage(): NetworkUsageModel? = selected().let {
        when (it) {
            TelegramBackendKind.LEGACY -> legacy.getNetworkUsage()
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getNetworkUsage()
        }
    }

    override suspend fun getNetworkStatisticsEnabled(): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.getNetworkStatisticsEnabled()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.getNetworkStatisticsEnabled()
    }

    override suspend fun setNetworkStatisticsEnabled(enabled: Boolean) = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.setNetworkStatisticsEnabled(enabled)
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.setNetworkStatisticsEnabled(enabled)
    }

    override suspend fun resetNetworkStatistics(): Boolean = when (selected()) {
        TelegramBackendKind.LEGACY -> legacy.resetNetworkStatistics()
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto.resetNetworkStatistics()
    }

    private fun selected(): TelegramBackendKind = checkNotNull(selectedBackend.value) {
        "Telegram backend selection is not loaded"
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
