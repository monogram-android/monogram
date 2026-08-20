package org.monogram.data.mtproto

import kotlinx.coroutines.CancellationException

internal fun interface MtProtoAccountStateResetter {
    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment)
}

internal class MtProtoAccountStateCleaner(
    private val authKeyPersistence: MtProtoAuthKeyPersistence,
    private val updateCursorStore: MtProtoUpdateCursorStore,
    private val pendingEnvelopeStore: MtProtoPendingEnvelopeStore,
    private val cloudObjectStager: MtProtoCloudObjectStager,
    private val userProjectionStore: MtProtoUserProjectionStore,
    private val chatProjectionStore: MtProtoChatProjectionStore,
    private val messageProjectionStore: MtProtoMessageProjectionStore,
    private val accountDcStore: MtProtoAccountDcStore = NoOpMtProtoAccountDcStore,
    private val dialogStore: MtProtoDialogStore = NoOpMtProtoDialogStore,
    private val draftStore: MtProtoDraftStore = NoOpMtProtoDraftStore,
    private val authorizationStore: MtProtoAccountAuthorizationStore = NoOpMtProtoAccountAuthorizationStore,
) : MtProtoAccountStateResetter {
    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) {
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
        failure = collectFailure(failure) {
            userProjectionStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            chatProjectionStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            messageProjectionStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            dialogStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            draftStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            accountDcStore.delete(accountSlot)
        }
        failure = collectFailure(failure) {
            authorizationStore.clear(accountSlot)
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
