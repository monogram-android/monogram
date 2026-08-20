package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.EditTitle
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatAbout
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.EditChatTitle

internal interface MtProtoChatSettingsRepository {
    suspend fun setTitle(chatId: Long, title: String)
    suspend fun setDescription(chatId: Long, description: String)
}

internal class MtProtoChatSettingsRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val chats: MtProtoChatProjectionStore,
    private val cloudObjectStager: MtProtoCloudObjectStager,
    private val accountSlot: String = "default",
) : MtProtoChatSettingsRepository {
    override suspend fun setTitle(chatId: Long, title: String) {
        require(title.isNotBlank()) { "MTProto chat title must not be blank" }
        val (scope, peer) = resolve(chatId)
        transportFactory.open(accountSlot).use { transport ->
            val updates = when (peer.type) {
                DialogPeerType.BASIC_GROUP -> transport.execute(EditChatTitle(peer.id, title))
                DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                    val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                        "Missing MTProto channel access hash: ${peer.id}"
                    }
                    transport.execute(EditTitle(InputChannel_d22292516d(peer.id, accessHash), title))
                }
                DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto cannot edit this chat title")
            }
            cloudObjectStager.stageLive(scope, updates)
        }
    }

    override suspend fun setDescription(chatId: Long, description: String) {
        val (scope, peer) = resolve(chatId)
        val inputPeer = when (peer.type) {
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val accessHash = requireNotNull(chats.get(scope, peer.id)?.accessHash) {
                    "Missing MTProto channel access hash: ${peer.id}"
                }
                InputPeerChannel(peer.id, accessHash)
            }
            DialogPeerType.PRIVATE, DialogPeerType.UNKNOWN -> error("MTProto cannot edit this chat description")
        }
        transportFactory.open(accountSlot).use { transport ->
            check(transport.execute(EditChatAbout(inputPeer, description))) {
                "messages.editChatAbout was rejected"
            }
        }
    }

    private suspend fun resolve(chatId: Long): Pair<MtProtoAuthKeyScope, TelegramPeerChatId.Peer> {
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val isChannel = if (chatId <= -CHANNEL_OFFSET - 1L) {
            chats.get(scope, -(chatId + CHANNEL_OFFSET))?.type == MtProtoChatType.CHANNEL
        } else {
            false
        }
        return scope to TelegramPeerChatId.decode(chatId, isChannel)
    }

    private companion object {
        const val CHANNEL_OFFSET = 1_000_000_000_000L
    }
}
