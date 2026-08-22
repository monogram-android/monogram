package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputChannel_d22292516d
import org.monogram.mtproto.tl.generated.cloud.layer223.InputUserSelf
import org.monogram.mtproto.tl.generated.cloud.layer223.channels.LeaveChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.DeleteChatUser

internal fun interface MtProtoLeaveChatRepository {
    suspend fun leave(chatIds: Set<Long>)
}

internal class MtProtoLeaveChatRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val chats: MtProtoChatProjectionStore,
    private val cloudObjectStager: MtProtoCloudObjectStager,
    private val dialogs: DialogSnapshotRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoLeaveChatRepository {
    override suspend fun leave(chatIds: Set<Long>) {
        if (chatIds.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            chatIds.forEach { chatId ->
                val peer = TelegramPeerChatId.decode(chatId)
                val updates = when (peer.type) {
                    DialogPeerType.BASIC_GROUP -> transport.execute(DeleteChatUser(false, peer.id, InputUserSelf))
                    DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                        val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                        val accessHash = requireNotNull(chat.accessHash) { "Missing MTProto channel access hash: ${peer.id}" }
                        transport.execute(LeaveChannel(InputChannel_d22292516d(peer.id, accessHash)))
                    }
                    DialogPeerType.PRIVATE -> error("MTProto cannot leave a private dialog")
                    DialogPeerType.UNKNOWN -> error("Cannot leave an unknown peer")
                }
                cloudObjectStager.stageLive(scope, updates)
            }
        } finally {
            transport.close()
        }
        dialogs.getDialogs(accountSlot)
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
