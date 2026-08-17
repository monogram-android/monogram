package org.monogram.data.mtproto

import kotlinx.coroutines.CancellationException

internal class MtProtoAccountStateCleaner(
    private val authKeyPersistence: MtProtoAuthKeyPersistence,
    private val updateCursorStore: MtProtoUpdateCursorStore,
) {
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        var failure: Throwable? = null
        try {
            authKeyPersistence.deleteAccount(accountSlot, environment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (authFailure: Throwable) {
            failure = authFailure
        }
        try {
            updateCursorStore.deleteAccount(accountSlot, environment)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cursorFailure: Throwable) {
            failure?.addSuppressed(cursorFailure) ?: run { failure = cursorFailure }
        }
        failure?.let { throw it }
    }
}
