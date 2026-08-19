package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.MtProtoReadHistoryRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.ReadHistory

internal class MtProtoReadHistoryRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoReadHistoryRepository {
    override suspend fun markRead(chatId: Long, peerType: DialogPeerType, maxMessageId: Long) {
        require(maxMessageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId, peerType)
        val transport = transportFactory.open(accountSlot)
        try {
            transport.execute(ReadHistory(peer = peer, maxId = maxMessageId.toInt()))
        } finally {
            transport.close()
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long, peerType: DialogPeerType): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId, peerType == DialogPeerType.CHANNEL)
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
            DialogPeerType.UNKNOWN -> error("Cannot mark an unknown peer read")
        }
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
