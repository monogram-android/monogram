package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetScheduledMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.SendScheduledMessages

internal interface MtProtoScheduledMessageOperations {
    suspend fun get(chatId: Long): List<MtProtoMessageReadModel>
    suspend fun sendNow(chatId: Long, messageId: Long)
}

internal class MtProtoScheduledMessageRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val userStore: MtProtoUserProjectionStore,
    private val chatStore: MtProtoChatProjectionStore,
    private val messageStore: MtProtoMessageProjectionStore,
    private val resultStager: MtProtoHistoryResultStager,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : MtProtoScheduledMessageOperations {
    override suspend fun get(chatId: Long): List<MtProtoMessageReadModel> {
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId)
        transportFactory.open(accountId).use { transport ->
            resultStager.stageScheduled(scope, transport.execute(GetScheduledMessages(toInputPeer(scope, peer), emptyList())))
        }
        return messageStore.getAll(scope, peer.type.toMessagePeerType(), peer.id)
            .filter { it.isScheduled && !it.isDeleted }
    }

    override suspend fun sendNow(chatId: Long, messageId: Long) {
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountId)
        val scope = MtProtoAuthKeyScope(accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId)
        transportFactory.open(accountId).use { transport ->
            messageStore.stageLive(scope, transport.execute(SendScheduledMessages(toInputPeer(scope, peer), listOf(messageId.toInt()))))
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): TelegramPeerChatId.Peer {
        if (chatId > 0L || chatId > -1_000_000_000_001L) return TelegramPeerChatId.decode(chatId)
        val peerId = -(chatId + 1_000_000_000_000L)
        val chat = requireNotNull(chatStore.get(scope, peerId)) { "Missing MTProto chat projection: $peerId" }
        return TelegramPeerChatId.decode(chatId, chat.type == MtProtoChatType.CHANNEL)
    }

    private suspend fun toInputPeer(scope: MtProtoAuthKeyScope, peer: TelegramPeerChatId.Peer): InputPeer = when (peer.type) {
        DialogPeerType.PRIVATE -> {
            val user = requireNotNull(userStore.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
            InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
        }
        DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
        DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
            val chat = requireNotNull(chatStore.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
            InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
        }
        DialogPeerType.UNKNOWN -> error("Cannot load scheduled messages for an unknown peer")
    }

    private fun DialogPeerType.toMessagePeerType() = when (this) {
        DialogPeerType.PRIVATE -> MtProtoMessagePeerType.USER
        DialogPeerType.BASIC_GROUP -> MtProtoMessagePeerType.GROUP
        DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> MtProtoMessagePeerType.CHANNEL
        DialogPeerType.UNKNOWN -> error("Unknown scheduled message peer")
    }

    private companion object { const val DEFAULT_ACCOUNT_ID = "default" }
}
