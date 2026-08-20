package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.monogram.data.mtproto.MtProtoSessionRepository
import org.monogram.domain.models.SessionModel
import org.monogram.domain.repository.SessionRepository

internal class TelegramBackendSessionRouter(
    selectionStore: TelegramBackendSelectionStore,
    private val legacyFactory: () -> SessionRepository,
    private val mtProto: MtProtoSessionRepository,
    scope: CoroutineScope,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : SessionRepository {
    private val selectedBackend = MutableStateFlow<TelegramBackendKind?>(null)
    private val legacy by lazy(LazyThreadSafetyMode.NONE, legacyFactory)

    init {
        scope.launch {
            selectionStore.observe(accountId).collect { selectedBackend.value = it }
        }
    }

    override suspend fun getActiveSessions(): List<SessionModel> = selected().getActiveSessions()
    override suspend fun terminateSession(sessionId: Long): Boolean = selected().terminateSession(sessionId)
    override suspend fun confirmQrCode(link: String): Boolean = selected().confirmQrCode(link)

    private fun selected(): SessionRepository = when (selectedBackend.value) {
        TelegramBackendKind.LEGACY -> legacy
        TelegramBackendKind.KOTLIN_MTPROTO -> mtProto
        null -> error("Telegram backend selection is not loaded")
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
