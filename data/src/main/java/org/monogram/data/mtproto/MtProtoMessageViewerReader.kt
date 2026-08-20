package org.monogram.data.mtproto

import org.monogram.domain.models.MessageViewerModel
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserTypeEnum
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.ReadParticipantDate_d00bb53fcf
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetMessageReadParticipants

internal fun interface MtProtoMessageViewerReader {
    suspend fun get(chatId: Long, messageId: Long): List<MessageViewerModel>
}

internal class MtProtoMessageViewerReaderImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoMessageViewerReader {
    override suspend fun get(chatId: Long, messageId: Long): List<MessageViewerModel> {
        require(messageId in 1..Int.MAX_VALUE) { "MTProto message id must fit a positive int" }
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val peer = resolvePeer(scope, chatId)
        val transport = transportFactory.open(accountSlot)
        try {
            return transport.execute(GetMessageReadParticipants(peer, messageId.toInt()))
                .map { record ->
                    val participant = record as? ReadParticipantDate_d00bb53fcf
                        ?: throw UnsupportedOperationException("MTProto read participant type is not available")
                    val user = requireNotNull(users.get(scope, participant.userId)) {
                        "Missing MTProto user projection for message viewer: ${participant.userId}"
                    }
                    MessageViewerModel(user.toUserModel(), participant.date)
                }
        } finally {
            transport.close()
        }
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            org.monogram.domain.models.DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            org.monogram.domain.models.DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            org.monogram.domain.models.DialogPeerType.SUPERGROUP,
            org.monogram.domain.models.DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" })
            }
            org.monogram.domain.models.DialogPeerType.UNKNOWN -> error("Cannot load viewers for an unknown peer")
        }
    }

    private fun MtProtoUserReadModel.toUserModel() = UserModel(
        id = userId,
        firstName = firstName.orEmpty(),
        lastName = lastName,
        username = username,
        phoneNumber = phone,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isPremium = isPremium,
        isVerified = isVerified,
        isScam = isScam,
        isFake = isFake,
        type = when {
            isDeleted -> UserTypeEnum.DELETED
            isBot -> UserTypeEnum.BOT
            else -> UserTypeEnum.REGULAR
        },
    )

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
