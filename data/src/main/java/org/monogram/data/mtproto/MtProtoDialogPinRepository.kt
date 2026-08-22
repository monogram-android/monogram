package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputDialogPeer_5b57e298d7
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ToggleDialogPin

internal fun interface MtProtoDialogPinRepository {
    suspend fun setPinned(chatIds: Set<Long>, pinned: Boolean)
}

internal class MtProtoDialogPinRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val dialogs: DialogSnapshotRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoDialogPinRepository {
    override suspend fun setPinned(chatIds: Set<Long>, pinned: Boolean) {
        if (chatIds.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peers = chatIds.map { InputDialogPeer_5b57e298d7(resolvePeer(scope, it)) }
        val transport = transportFactory.open(accountSlot)
        try {
            peers.forEach { peer -> transport.execute(ToggleDialogPin(pinned, peer)) }
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
            DialogPeerType.UNKNOWN -> error("Cannot pin an unknown peer")
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
