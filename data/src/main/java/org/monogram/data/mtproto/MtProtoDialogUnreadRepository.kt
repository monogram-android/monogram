package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputDialogPeer_5b57e298d7
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.MarkDialogUnread

internal fun interface MtProtoDialogUnreadRepository {
    suspend fun setUnread(chatIds: Set<Long>, unread: Boolean)
}

internal class MtProtoDialogUnreadRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val dialogStore: MtProtoDialogStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoDialogUnreadRepository {
    override suspend fun setUnread(chatIds: Set<Long>, unread: Boolean) {
        if (chatIds.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            chatIds.forEach { chatId ->
                val peer = resolvePeer(scope, chatId)
                check(transport.execute(MarkDialogUnread(unread, null, InputDialogPeer_5b57e298d7(peer))))
                val decoded = TelegramPeerChatId.decode(chatId)
                dialogStore.setUnreadMark(scope, decoded.toMessagePeerType(), decoded.id, unread)
            }
        } finally {
            transport.close()
        }
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
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto chat access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("Cannot mark an unknown peer unread")
        }
    }

    private fun TelegramPeerChatId.Peer.toMessagePeerType() = when (type) {
        DialogPeerType.PRIVATE -> MtProtoMessagePeerType.USER
        DialogPeerType.BASIC_GROUP -> MtProtoMessagePeerType.GROUP
        DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> MtProtoMessagePeerType.CHANNEL
        DialogPeerType.UNKNOWN -> error("Cannot mark an unknown peer unread")
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
