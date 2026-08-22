package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.MtProtoMessageDeletionRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.DeleteMessages as DeleteChannelMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteMessages as DeleteMessages

internal class MtProtoMessageDeletionRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val chats: MtProtoChatProjectionStore,
    private val messages: MtProtoMessageProjectionStore = NoOpMtProtoMessageProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoMessageDeletionRepository {
    override suspend fun delete(chatId: Long, peerType: DialogPeerType, messageIds: List<Long>, revoke: Boolean) {
        require(messageIds.isNotEmpty()) { "At least one message id is required" }
        val ids = messageIds.map {
            require(it in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
            it.toInt()
        }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = TelegramPeerChatId.decode(chatId, peerType == DialogPeerType.CHANNEL)
        val transport = transportFactory.open(accountSlot)
        try {
            when (peer.type) {
                DialogPeerType.PRIVATE, DialogPeerType.BASIC_GROUP -> transport.execute(DeleteMessages(revoke, ids))
                DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                    val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                    transport.execute(DeleteChannelMessages(InputChannel_d22292516d(peer.id, requireNotNull(chat.accessHash)), ids))
                }
                DialogPeerType.UNKNOWN -> error("Cannot delete from an unknown peer")
            }
            messages.markDeleted(scope, peer.type.toMessagePeerType(), peer.id, ids)
        } finally {
            transport.close()
        }
    }

    private fun DialogPeerType.toMessagePeerType() = when (this) {
        DialogPeerType.PRIVATE -> MtProtoMessagePeerType.USER
        DialogPeerType.BASIC_GROUP -> MtProtoMessagePeerType.GROUP
        DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> MtProtoMessagePeerType.CHANNEL
        DialogPeerType.UNKNOWN -> error("Cannot delete from an unknown peer")
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
