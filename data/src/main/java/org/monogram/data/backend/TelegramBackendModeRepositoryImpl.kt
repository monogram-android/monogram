package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.monogram.domain.repository.TelegramBackendMode
import org.monogram.domain.repository.TelegramBackendModeRepository

internal class TelegramBackendModeRepositoryImpl(
    selectionStore: TelegramBackendSelectionStore,
    scope: CoroutineScope,
    accountId: String = DEFAULT_ACCOUNT_ID,
) : TelegramBackendModeRepository {
    private val _backendMode = MutableStateFlow(TelegramBackendMode.UNKNOWN)
    override val backendMode = _backendMode.asStateFlow()

    init {
        scope.launch {
            selectionStore.observe(accountId)
                .distinctUntilChanged()
                .collect { backend -> _backendMode.value = backend.toDomain() }
        }
    }

    private fun TelegramBackendKind.toDomain() = when (this) {
        TelegramBackendKind.LEGACY -> TelegramBackendMode.LEGACY
        TelegramBackendKind.KOTLIN_MTPROTO -> TelegramBackendMode.KOTLIN_MTPROTO
    }

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
