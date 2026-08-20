package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoChatProjectionDao
import org.monogram.data.db.dao.MtProtoDialogProjectionDao
import org.monogram.data.db.dao.MtProtoMessageProjectionDao
import org.monogram.data.db.dao.MtProtoUserProjectionDao
import org.monogram.data.db.model.MtProtoChatProjectionEntity
import org.monogram.data.db.model.MtProtoDialogProjectionEntity
import org.monogram.data.db.model.MtProtoMessageProjectionEntity
import org.monogram.data.db.model.MtProtoUserProjectionEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.Dialog_cf9860a8bd
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDialogPinned
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDialogUnreadMark
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateNotifySettings
import org.monogram.mtproto.tl.generated.cloud.layer223.NotifyPeer_4fa2c93506
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerNotifySettings_474d6bbc59

internal data class MtProtoDialogMessagePreview(
    val messageId: Int,
    val senderType: MtProtoMessagePeerType?,
    val senderId: Long?,
    val date: Int,
    val text: String?,
    val isService: Boolean,
    val isDeleted: Boolean,
    val isOutgoing: Boolean,
    val hasMedia: Boolean,
)

internal data class MtProtoDialogReadModel(
    val peerType: MtProtoMessagePeerType,
    val peerKind: MtProtoDialogPeerKind,
    val peerId: Long,
    val title: String?,
    val username: String?,
    val isPeerResolved: Boolean,
    val isPeerDeleted: Boolean,
    val isPeerForbidden: Boolean,
    val unreadCount: Int,
    val unreadMentionsCount: Int,
    val unreadReactionsCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val latestMessage: MtProtoDialogMessagePreview,
)

internal enum class MtProtoDialogPeerKind {
    PRIVATE,
    BASIC_GROUP,
    SUPERGROUP,
    CHANNEL,
    UNKNOWN,
}

internal interface MtProtoDialogStore {
    suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoDialogReadModel>

    suspend fun upsert(scope: MtProtoAuthKeyScope, dialogs: List<Dialog_cf9860a8bd>) = Unit

    suspend fun updateTopMessage(scope: MtProtoAuthKeyScope, peerType: MtProtoMessagePeerType, peerId: Long, messageId: Int) = Unit

    suspend fun updatePinned(scope: MtProtoAuthKeyScope, update: UpdateDialogPinned) = Unit

    suspend fun updateUnreadMark(scope: MtProtoAuthKeyScope, update: UpdateDialogUnreadMark) = Unit

    suspend fun updateNotifySettings(scope: MtProtoAuthKeyScope, update: UpdateNotifySettings) = Unit

    suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) = Unit
}

internal object NoOpMtProtoDialogStore : MtProtoDialogStore {
    override suspend fun getAll(scope: MtProtoAuthKeyScope) = emptyList<MtProtoDialogReadModel>()
}

internal class MtProtoRoomDialogStore(
    private val messageDao: MtProtoMessageProjectionDao,
    private val userDao: MtProtoUserProjectionDao,
    private val chatDao: MtProtoChatProjectionDao,
    private val dialogDao: MtProtoDialogProjectionDao,
) : MtProtoDialogStore {
    override suspend fun updateTopMessage(
        scope: MtProtoAuthKeyScope,
        peerType: MtProtoMessagePeerType,
        peerId: Long,
        messageId: Int,
    ) = dialogDao.updateTopMessage(
        accountSlot = scope.accountSlot,
        environment = scope.environment.storageName,
        dcId = scope.dcId,
        peerType = peerType.name,
        peerId = peerId,
        messageId = messageId,
        updatedAt = System.currentTimeMillis(),
    )

    override suspend fun updatePinned(scope: MtProtoAuthKeyScope, update: UpdateDialogPinned) {
        val peer = (update.peer as? org.monogram.mtproto.tl.generated.cloud.layer223.DialogPeer_2011bde660)?.peer
            ?: return
        val key = peer.toProjectionKey()
        dialogDao.updatePinned(scope.accountSlot, scope.environment.storageName, scope.dcId, key.first.name, key.second, update.pinned, update.folderId, System.currentTimeMillis())
    }

    override suspend fun updateNotifySettings(scope: MtProtoAuthKeyScope, update: UpdateNotifySettings) {
        val peer = (update.peer as? NotifyPeer_4fa2c93506)?.peer ?: return
        val settings = update.notifySettings as? PeerNotifySettings_474d6bbc59 ?: return
        val key = peer.toProjectionKey()
        dialogDao.updateMuted(
            scope.accountSlot,
            scope.environment.storageName,
            scope.dcId,
            key.first.name,
            key.second,
            settings.muteUntil?.let { it > System.currentTimeMillis() / 1000L } == true,
            System.currentTimeMillis(),
        )
    }

    override suspend fun updateUnreadMark(scope: MtProtoAuthKeyScope, update: UpdateDialogUnreadMark) {
        val peer = (update.peer as? org.monogram.mtproto.tl.generated.cloud.layer223.DialogPeer_2011bde660)?.peer
            ?: return
        val key = peer.toProjectionKey()
        dialogDao.updateUnreadMark(scope.accountSlot, scope.environment.storageName, scope.dcId, key.first.name, key.second, update.unread, System.currentTimeMillis())
    }

    override suspend fun deleteAccount(accountSlot: String, environment: MtProtoEnvironment) =
        dialogDao.deleteAccount(accountSlot, environment.storageName)

    override suspend fun upsert(scope: MtProtoAuthKeyScope, dialogs: List<Dialog_cf9860a8bd>) {
        if (dialogs.isEmpty()) return
        dialogDao.upsertAll(dialogs.map { dialog ->
            val (peerType, peerId) = dialog.peer.toProjectionKey()
            MtProtoDialogProjectionEntity(
                accountSlot = scope.accountSlot,
                environment = scope.environment.storageName,
                dcId = scope.dcId,
                peerType = peerType.name,
                peerId = peerId,
                pinned = dialog.pinned,
                muted = (dialog.notifySettings as? PeerNotifySettings_474d6bbc59)?.muteUntil
                    ?.let { it > System.currentTimeMillis() / 1000L } == true,
                unreadMark = dialog.unreadMark,
                topMessageId = dialog.topMessage,
                unreadCount = dialog.unreadCount,
                unreadMentionsCount = dialog.unreadMentionsCount,
                unreadReactionsCount = dialog.unreadReactionsCount,
                folderId = dialog.folderId,
                updatedAt = System.currentTimeMillis(),
            )
        })
    }

    override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoDialogReadModel> {
        val accountSlot = scope.accountSlot
        val environment = scope.environment.storageName
        val dcId = scope.dcId
        val users = userDao.getAll(accountSlot, environment, dcId).associateBy { it.userId }
        val chats = chatDao.getAll(accountSlot, environment, dcId).associateBy { it.chatId }
        val latest = messageDao.getLatestByPeer(accountSlot, environment, dcId)
            .associateBy { it.peerType to it.peerId }
        val persisted = dialogDao.getAll(accountSlot, environment, dcId)
            .mapNotNull { dialog ->
                val peerType = MtProtoMessagePeerType.valueOf(dialog.peerType)
                dialog.toDialog(
                    peerType = peerType,
                    user = users[dialog.peerId],
                    chat = chats[dialog.peerId],
                    message = latest[dialog.peerType to dialog.peerId],
                )
            }
        val persistedKeys = persisted.map { it.peerType.name to it.peerId }.toSet()
        return persisted + latest
            .filterKeys { it !in persistedKeys }
            .map { (key, message) ->
                val peerType = MtProtoMessagePeerType.valueOf(key.first)
                when (peerType) {
                    MtProtoMessagePeerType.USER -> message.toDialog(peerType, users[key.second], null)
                    MtProtoMessagePeerType.GROUP,
                    MtProtoMessagePeerType.CHANNEL -> message.toDialog(peerType, null, chats[key.second])
                }
            }
    }

    private fun MtProtoMessageProjectionEntity.toDialog(
        peerType: MtProtoMessagePeerType,
        user: MtProtoUserProjectionEntity?,
        chat: MtProtoChatProjectionEntity?,
    ) = MtProtoDialogReadModel(
        peerType = peerType,
        peerKind = peerKind(peerType, chat),
        peerId = peerId,
        title = user?.displayTitle() ?: chat?.title,
        username = user?.username ?: chat?.username,
        isPeerResolved = user != null || chat != null,
        isPeerDeleted = user?.isDeleted ?: chat?.isDeleted ?: false,
        isPeerForbidden = chat?.isForbidden ?: false,
        unreadCount = 0,
        unreadMentionsCount = 0,
        unreadReactionsCount = 0,
        isPinned = false,
        isMuted = false,
        latestMessage = MtProtoDialogMessagePreview(
            messageId = messageId,
            senderType = senderType?.let(MtProtoMessagePeerType::valueOf),
            senderId = senderId,
            date = date,
            text = text,
            isService = isService,
            isDeleted = isDeleted,
            isOutgoing = isOutgoing,
            hasMedia = hasMedia,
        ),
    )

    private fun MtProtoDialogProjectionEntity.toDialog(
        peerType: MtProtoMessagePeerType,
        user: MtProtoUserProjectionEntity?,
        chat: MtProtoChatProjectionEntity?,
        message: MtProtoMessageProjectionEntity?,
    ) = MtProtoDialogReadModel(
        peerType = peerType,
        peerKind = peerKind(peerType, chat),
        peerId = peerId,
        title = user?.displayTitle() ?: chat?.title,
        username = user?.username ?: chat?.username,
        isPeerResolved = user != null || chat != null,
        isPeerDeleted = user?.isDeleted ?: chat?.isDeleted ?: false,
        isPeerForbidden = chat?.isForbidden ?: false,
        unreadCount = this.unreadCount,
        unreadMentionsCount = this.unreadMentionsCount,
        unreadReactionsCount = this.unreadReactionsCount,
        isPinned = pinned,
        isMuted = muted,
        latestMessage = MtProtoDialogMessagePreview(
            messageId = message?.messageId ?: topMessageId,
            senderType = message?.senderType?.let(MtProtoMessagePeerType::valueOf),
            senderId = message?.senderId,
            date = message?.date ?: 0,
            text = message?.text,
            isService = message?.isService ?: false,
            isDeleted = message?.isDeleted ?: false,
            isOutgoing = message?.isOutgoing ?: false,
            hasMedia = message?.hasMedia ?: false,
        ),
    )

    private fun org.monogram.mtproto.tl.generated.cloud.layer223.Peer.toProjectionKey() = when (this) {
        is org.monogram.mtproto.tl.generated.cloud.layer223.PeerUser -> MtProtoMessagePeerType.USER to userId
        is org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat -> MtProtoMessagePeerType.GROUP to chatId
        is org.monogram.mtproto.tl.generated.cloud.layer223.PeerChannel -> MtProtoMessagePeerType.CHANNEL to channelId
    }

    private fun peerKind(
        peerType: MtProtoMessagePeerType,
        chat: MtProtoChatProjectionEntity?,
    ) = when (peerType) {
        MtProtoMessagePeerType.USER -> MtProtoDialogPeerKind.PRIVATE
        MtProtoMessagePeerType.GROUP -> MtProtoDialogPeerKind.BASIC_GROUP
        MtProtoMessagePeerType.CHANNEL -> when (chat?.type?.let(MtProtoChatType::valueOf)) {
            MtProtoChatType.SUPERGROUP -> MtProtoDialogPeerKind.SUPERGROUP
            MtProtoChatType.CHANNEL -> MtProtoDialogPeerKind.CHANNEL
            else -> MtProtoDialogPeerKind.UNKNOWN
        }
    }

    private fun MtProtoUserProjectionEntity.displayTitle(): String? =
        listOfNotNull(firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { username.orEmpty() }
            .ifBlank { null }
}
