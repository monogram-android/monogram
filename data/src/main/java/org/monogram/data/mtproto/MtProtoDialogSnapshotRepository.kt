package org.monogram.data.mtproto

import org.monogram.domain.models.DialogMessagePreviewModel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetDialogs
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.DialogSnapshotModel
import org.monogram.domain.repository.DialogSnapshotRepository

internal class MtProtoDialogSnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val dialogStore: MtProtoDialogStore,
    private val sessionFactory: TelegramMtProtoSessionFactory? = null,
    private val resultStager: MtProtoDialogResultStager? = null,
) : DialogSnapshotRepository {
    override suspend fun getDialogs(accountId: String): List<DialogSnapshotModel> {
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        if (sessionFactory != null && resultStager != null) {
            sessionFactory.open(accountId).use { transport ->
                val result = transport.execute(
                    GetDialogs(
                        excludePinned = false,
                        folderId = null,
                        offsetDate = 0,
                        offsetId = 0,
                        offsetPeer = InputPeerEmpty,
                        limit = INITIAL_PAGE_SIZE,
                        hash = 0L,
                    ),
                )
                check(resultStager.stage(scope, result)) { "Unsupported messages.getDialogs result" }
            }
        }
        return dialogStore.getAll(scope).map { it.toDomain() }
    }

    private fun MtProtoDialogReadModel.toDomain() = DialogSnapshotModel(
        peerId = peerId,
        peerType = when (peerKind) {
            MtProtoDialogPeerKind.PRIVATE -> DialogPeerType.PRIVATE
            MtProtoDialogPeerKind.BASIC_GROUP -> DialogPeerType.BASIC_GROUP
            MtProtoDialogPeerKind.SUPERGROUP -> DialogPeerType.SUPERGROUP
            MtProtoDialogPeerKind.CHANNEL -> DialogPeerType.CHANNEL
            MtProtoDialogPeerKind.UNKNOWN -> DialogPeerType.UNKNOWN
        },
        title = title,
        username = username,
        isPeerResolved = isPeerResolved,
        isPeerDeleted = isPeerDeleted,
        isPeerForbidden = isPeerForbidden,
        latestMessage = DialogMessagePreviewModel(
            messageId = latestMessage.messageId.toLong(),
            senderId = latestMessage.senderId,
            date = latestMessage.date,
            text = latestMessage.text,
            isService = latestMessage.isService,
            isDeleted = latestMessage.isDeleted,
            isOutgoing = latestMessage.isOutgoing,
            hasMedia = latestMessage.hasMedia,
        ),
    )

    private companion object {
        const val INITIAL_PAGE_SIZE = 100
    }
}
