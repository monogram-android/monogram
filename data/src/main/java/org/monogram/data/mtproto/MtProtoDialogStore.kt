package org.monogram.data.mtproto

import org.monogram.data.db.dao.MtProtoChatProjectionDao
import org.monogram.data.db.dao.MtProtoMessageProjectionDao
import org.monogram.data.db.dao.MtProtoUserProjectionDao
import org.monogram.data.db.model.MtProtoChatProjectionEntity
import org.monogram.data.db.model.MtProtoMessageProjectionEntity
import org.monogram.data.db.model.MtProtoUserProjectionEntity

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
    val peerId: Long,
    val title: String?,
    val username: String?,
    val isPeerResolved: Boolean,
    val isPeerDeleted: Boolean,
    val isPeerForbidden: Boolean,
    val latestMessage: MtProtoDialogMessagePreview,
)

internal interface MtProtoDialogStore {
    suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoDialogReadModel>
}

internal class MtProtoRoomDialogStore(
    private val messageDao: MtProtoMessageProjectionDao,
    private val userDao: MtProtoUserProjectionDao,
    private val chatDao: MtProtoChatProjectionDao,
) : MtProtoDialogStore {
    override suspend fun getAll(scope: MtProtoAuthKeyScope): List<MtProtoDialogReadModel> {
        val accountSlot = scope.accountSlot
        val environment = scope.environment.storageName
        val dcId = scope.dcId
        val users = userDao.getAll(accountSlot, environment, dcId).associateBy { it.userId }
        val chats = chatDao.getAll(accountSlot, environment, dcId).associateBy { it.chatId }
        return messageDao.getLatestByPeer(accountSlot, environment, dcId).map { message ->
            when (val peerType = MtProtoMessagePeerType.valueOf(message.peerType)) {
                MtProtoMessagePeerType.USER -> message.toDialog(peerType, users[message.peerId], null)
                MtProtoMessagePeerType.GROUP,
                MtProtoMessagePeerType.CHANNEL -> message.toDialog(peerType, null, chats[message.peerId])
            }
        }
    }

    private fun MtProtoMessageProjectionEntity.toDialog(
        peerType: MtProtoMessagePeerType,
        user: MtProtoUserProjectionEntity?,
        chat: MtProtoChatProjectionEntity?,
    ) = MtProtoDialogReadModel(
        peerType = peerType,
        peerId = peerId,
        title = user?.displayTitle() ?: chat?.title,
        username = user?.username ?: chat?.username,
        isPeerResolved = user != null || chat != null,
        isPeerDeleted = user?.isDeleted ?: chat?.isDeleted ?: false,
        isPeerForbidden = chat?.isForbidden ?: false,
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

    private fun MtProtoUserProjectionEntity.displayTitle(): String? =
        listOfNotNull(firstName, lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { username.orEmpty() }
            .ifBlank { null }
}
