package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputFolderPeer_752d9a4fbc
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.folders.EditPeerFolders

internal fun interface MtProtoArchiveRepository {
    suspend fun setArchived(chatIds: Set<Long>, archived: Boolean)
}

internal class MtProtoArchiveRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val dialogs: DialogSnapshotRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoArchiveRepository {
    override suspend fun setArchived(chatIds: Set<Long>, archived: Boolean) {
        if (chatIds.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peers = chatIds.map { chatId ->
            InputFolderPeer_752d9a4fbc(resolvePeer(scope, chatId), if (archived) ARCHIVE_FOLDER_ID else MAIN_FOLDER_ID)
        }
        val transport = transportFactory.open(accountSlot)
        try {
            transport.execute(EditPeerFolders(peers))
        } finally {
            transport.close()
        }
        dialogs.getDialogs(accountSlot)
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("Cannot archive an unknown peer")
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
        const val MAIN_FOLDER_ID = 0
        const val ARCHIVE_FOLDER_ID = 1
    }
}
