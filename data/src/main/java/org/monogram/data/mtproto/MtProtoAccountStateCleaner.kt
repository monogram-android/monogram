package org.monogram.data.mtproto

import kotlinx.coroutines.CancellationException

internal class MtProtoAccountStateCleaner(
    private val authKeyPersistence: MtProtoAuthKeyPersistence,
    private val updateCursorStore: MtProtoUpdateCursorStore,
    private val pendingEnvelopeStore: MtProtoPendingEnvelopeStore,
    private val cloudObjectStager: MtProtoCloudObjectStager,
) {
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
        var failure: Throwable? = null
        failure = collectFailure(failure) {
            authKeyPersistence.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            updateCursorStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            pendingEnvelopeStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            cloudObjectStager.deleteAccount(accountSlot, environment)
        }
        failure?.let { throw it }
    }

    private suspend fun collectFailure(
        current: Throwable?,
        cleanup: suspend () -> Unit,
    ): Throwable? = try {
        cleanup()
        current
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cleanupFailure: Throwable) {
        current?.apply { addSuppressed(cleanupFailure) } ?: cleanupFailure
    }
}
