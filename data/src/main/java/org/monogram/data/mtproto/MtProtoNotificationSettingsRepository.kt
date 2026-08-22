package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import org.monogram.data.mtproto.MtProtoChatType
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.NotificationSettingsRepository
import org.monogram.domain.repository.NotificationSettingsRepository.TdNotificationScope
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyBroadcasts
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyChats
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyPeer_c75b710401
import org.monogram.mtproto.tl.generated.cloud.layer223.InputNotifyUsers
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerNotifySettings_6185e07dc9
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerNotifySettings_474d6bbc59
import org.monogram.mtproto.tl.generated.cloud.layer223.account.GetNotifySettings
import org.monogram.mtproto.tl.generated.cloud.layer223.account.UpdateNotifySettings

internal class MtProtoNotificationSettingsRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val transportFactory: MtProtoSessionTransportFactory,
    private val users: MtProtoUserProjectionStore,
    private val chats: MtProtoChatProjectionStore,
    private val dialogs: MtProtoDialogSnapshotRepository? = null,
    private val chatList: ChatListRepository? = null,
    private val accountSlot: String = DEFAULT_ACCOUNT_SLOT,
) : NotificationSettingsRepository {
    override suspend fun getNotificationSettings(scope: TdNotificationScope): Boolean {
        val result = requestGlobal(scope)
        return result.enabled()
    }

    override suspend fun setNotificationSettings(scope: TdNotificationScope, enabled: Boolean) {
        val transport = transportFactory.open(accountSlot)
        try {
            check(transport.execute(UpdateNotifySettings(scope.toInputNotifyPeer(), enabled.toInputSettings())))
        } finally {
            transport.close()
        }
    }

    override suspend fun getExceptions(scope: TdNotificationScope): List<ChatModel> {
        val globalEnabled = getNotificationSettings(scope)
        val snapshotRepository = requireNotNull(dialogs) { "MTProto dialog snapshots are unavailable" }
        val chatsRepository = requireNotNull(chatList) { "MTProto chat list is unavailable" }
        return snapshotRepository.getDialogs(accountSlot)
            .filter { it.toNotificationScope() == scope && it.isMuted != !globalEnabled }
            .mapNotNull { chatsRepository.getChatById(TelegramPeerChatId.encode(it.peerType, it.peerId)) }
    }

    override suspend fun setChatNotificationSettings(chatId: Long, enabled: Boolean) {
        val scope = scope()
        val transport = transportFactory.open(accountSlot)
        try {
            check(transport.execute(UpdateNotifySettings(
                InputNotifyPeer_c75b710401(resolvePeer(scope, chatId)),
                enabled.toInputSettings(),
            )))
        } finally {
            transport.close()
        }
        requireNotNull(dialogs) { "MTProto dialog snapshots are unavailable" }.getDialogs(accountSlot)
    }

    override suspend fun resetChatNotificationSettings(chatId: Long) {
        val scope = scope()
        val transport = transportFactory.open(accountSlot)
        try {
            check(transport.execute(UpdateNotifySettings(
                InputNotifyPeer_c75b710401(resolvePeer(scope, chatId)),
                InputPeerNotifySettings_6185e07dc9(null, null, null, null, null, null, null),
            )))
        } finally {
            transport.close()
        }
        requireNotNull(dialogs) { "MTProto dialog snapshots are unavailable" }.getDialogs(accountSlot)
    }

    private suspend fun requestGlobal(scope: TdNotificationScope): PeerNotifySettings_474d6bbc59 {
        val transport = transportFactory.open(accountSlot)
        return try {
            transport.execute(GetNotifySettings(scope.toInputNotifyPeer())) as PeerNotifySettings_474d6bbc59
        } finally {
            transport.close()
        }
    }

    private suspend fun scope(): MtProtoAuthKeyScope {
        val config = configSource.createForAccount(accountSlot)
        return MtProtoAuthKeyScope(accountSlot, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(users.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash))
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chats.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash))
            }
            DialogPeerType.UNKNOWN -> error("Cannot resolve notification peer")
        }
    }

    private fun TdNotificationScope.toInputNotifyPeer() = when (this) {
        TdNotificationScope.PRIVATE_CHATS -> InputNotifyUsers
        TdNotificationScope.GROUPS -> InputNotifyChats
        TdNotificationScope.CHANNELS -> InputNotifyBroadcasts
    }

    private fun Boolean.toInputSettings() = InputPeerNotifySettings_6185e07dc9(
        showPreviews = null,
        silent = !this,
        muteUntil = if (this) 0 else Int.MAX_VALUE,
        sound = null,
        storiesMuted = null,
        storiesHideSender = null,
        storiesSound = null,
    )

    private fun PeerNotifySettings_474d6bbc59.enabled(): Boolean {
        val until = muteUntil
        return silent != true && (until == null || until <= (System.currentTimeMillis() / 1000).toInt())
    }

    private fun org.monogram.domain.models.DialogSnapshotModel.toNotificationScope() = when (peerType) {
        DialogPeerType.PRIVATE -> TdNotificationScope.PRIVATE_CHATS
        DialogPeerType.BASIC_GROUP, DialogPeerType.SUPERGROUP -> TdNotificationScope.GROUPS
        DialogPeerType.CHANNEL -> TdNotificationScope.CHANNELS
        DialogPeerType.UNKNOWN -> null
    }

    private companion object { const val DEFAULT_ACCOUNT_SLOT = "default" }
}
