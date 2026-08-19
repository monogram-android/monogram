package org.monogram.data.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.monogram.data.mtproto.MtProtoAccountStateResetter
import org.monogram.data.mtproto.MtProtoEnvironment

/**
 * Switches a single account only after invalidating all state owned by its previous backend.
 *
 * The selection remains unchanged when cleanup fails. The mutex prevents overlapping in-process
 * transitions from exposing two active backend lifecycles for the same account.
 */
internal class TelegramBackendSwitchService(
    private val selectionStore: TelegramBackendSelectionStore,
    private val legacyActiveAccountBinding: LegacyActiveAccountBinding,
    private val mtProtoAccountStateResetter: MtProtoAccountStateResetter,
) {
    private val switchMutex = Mutex()

    suspend fun switch(accountId: String, targetBackend: TelegramBackendKind) {
        switchMutex.withLock {
            val sourceBackend = selectionStore.get(accountId)
            if (sourceBackend == targetBackend) return

            cleanup(sourceBackend, accountId)
            try {
                selectionStore.select(accountId, targetBackend)
                activate(targetBackend, accountId)
            } catch (selectionFailure: Throwable) {
                if (sourceBackend == TelegramBackendKind.LEGACY) {
                    legacyActiveAccountBinding.bind(accountId)
                }
                throw selectionFailure
            }
        }
    }

    private suspend fun cleanup(backend: TelegramBackendKind, accountId: String) {
        when (backend) {
            TelegramBackendKind.LEGACY -> legacyActiveAccountBinding.clear(accountId)
            TelegramBackendKind.KOTLIN_MTPROTO -> mtProtoAccountStateResetter.deleteAccount(
                accountSlot = accountId,
                environment = MtProtoEnvironment.PRODUCTION,
            )
        }
    }

    private fun activate(backend: TelegramBackendKind, accountId: String) {
        when (backend) {
            TelegramBackendKind.LEGACY -> legacyActiveAccountBinding.bind(accountId)
            TelegramBackendKind.KOTLIN_MTPROTO -> Unit
        }
    }
}
