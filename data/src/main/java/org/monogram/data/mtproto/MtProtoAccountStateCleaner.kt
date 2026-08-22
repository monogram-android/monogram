package org.monogram.data.mtproto

import kotlinx.coroutines.CancellationException

/**
 * Deletes all *local* MTProto data for one account slot: persisted auth keys, update cursors,
 * pending envelopes, staged cloud objects, user/chat/message/dialog/draft/story projections,
 * file handles, and the account authorization/DC markers.
 *
 * This never touches the Telegram server: session revocation happens separately through
 * `auth.logOut`, and Telegram account deletion (`account.deleteAccount`) is unrelated.
 */
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
    private val fileHandleStore: MtProtoFileHandleStore = NoOpMtProtoFileHandleStore,
    private val photoLocationStore: MtProtoPhotoLocationStore = NoOpMtProtoPhotoLocationStore,
    private val storyProjectionStore: MtProtoStoryProjectionStore = NoOpMtProtoStoryProjectionStore,
    private val storyStealthModeStore: MtProtoStoryStealthModeStore = NoOpMtProtoStoryStealthModeStore,
    private val secretChatStateStore: MtProtoSecretChatStateStore = NoOpMtProtoSecretChatStateStore,
    private val pollPayloads: MtProtoPollPayloadStore? = null,
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
            fileHandleStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            photoLocationStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            storyProjectionStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            storyStealthModeStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            accountDcStore.delete(accountSlot)
        }
        failure = collectFailure(failure) {
            secretChatStateStore.deleteAccount(accountSlot, environment)
        }
        failure = collectFailure(failure) {
            pollPayloads?.deleteAccount(accountSlot, environment)
        }
        failure?.let { throw it }
        authorizationStore.clear(accountSlot)
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
