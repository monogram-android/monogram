package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.DialogSnapshotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyPeer_c75b710401
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerNotifySettings_6185e07dc9
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateNotifySettings

internal fun interface MtProtoMuteRepository {
    suspend fun setMuted(chatIds: Set<Long>, muted: Boolean)
}

internal class MtProtoMuteRepositoryImpl(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val dialogs: DialogSnapshotRepository,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : MtProtoMuteRepository {
    override suspend fun setMuted(chatIds: Set<Long>, muted: Boolean) {
        if (chatIds.isEmpty()) return
        val config = configSource.createForAccount(accountSlot)
        val scope = MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        val transport = transportFactory.open(accountSlot)
        try {
            chatIds.forEach { chatId ->
                val peer = resolvePeer(scope, chatId)
                transport.execute(
                    UpdateNotifySettings(
                        InputNotifyPeer_c75b710401(peer),
                        InputPeerNotifySettings_6185e07dc9(
                            showPreviews = null,
                            silent = null,
                            muteUntil = if (muted) Int.MAX_VALUE else 0,
                            sound = null,
                            storiesMuted = null,
                            storiesHideSender = null,
                            storiesSound = null,
                        ),
                    )
                )
            }
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
            DialogPeerType.UNKNOWN -> error("Cannot mute an unknown peer")
        }
    }

    private companion object {
        const val DEFAULT_ACCOUNT_SLOT = "default"
    }
}
