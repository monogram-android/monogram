package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageHistoryCursorModel
import org.monogram.domain.models.MessageHistorySnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.TelegramPeerChatId
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.repository.MessageHistorySnapshotRepository
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeer
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChannel
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerUser
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetHistory

internal class MtProtoMessageHistorySnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val messageStore: MtProtoMessageProjectionStore,
    private val sessionFactory: TelegramMtProtoSessionFactory? = null,
    private val userStore: MtProtoUserProjectionStore? = null,
    private val chatStore: MtProtoChatProjectionStore? = null,
    private val resultStager: MtProtoHistoryResultStager? = null,
) : MessageHistorySnapshotRepository {
    override suspend fun getHistory(request: MessageHistorySnapshotRequest): MessageHistorySnapshotPage {
        require(request.limit in 1..MAX_PAGE_SIZE) { "History page size must be between 1 and $MAX_PAGE_SIZE" }
        val config = configSource.createForAccount(request.accountId)
        val scope = MtProtoAuthKeyScope(request.accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
        if (sessionFactory != null && userStore != null && chatStore != null && resultStager != null) {
            sessionFactory.open(request.accountId).use { transport ->
                resultStager.stage(
                    scope,
                    transport.execute(
                        GetHistory(
                            peer = resolvePeer(scope, request.peerType, request.peerId),
                            offsetId = request.before?.messageId?.toMtProtoMessageId() ?: 0,
                            offsetDate = request.before?.date ?: 0,
                            addOffset = 0,
                            limit = request.limit,
                            maxId = 0,
                            minId = 0,
                            hash = 0L,
                        ),
                    ),
                )
            }
        }
        val messages = messageStore.getPage(
            scope = scope,
            peerType = request.peerType.toMtProtoPeerType(),
            peerId = request.peerId,
            before = request.before?.let {
                MtProtoMessageHistoryCursor(it.date, it.messageId.toMtProtoMessageId())
            },
            limit = request.limit,
        )
        return MessageHistorySnapshotPage(
            messages = messages.map { it.toDomain() },
            nextCursor = messages.lastOrNull()
                ?.takeIf { messages.size == request.limit }
                ?.let { MessageHistoryCursorModel(it.date, it.messageId.toLong()) },
        )
    }

    private suspend fun resolvePeer(scope: MtProtoAuthKeyScope, peerType: DialogPeerType, chatId: Long): InputPeer {
        val peer = TelegramPeerChatId.decode(chatId, peerType == DialogPeerType.CHANNEL)
        return when (peer.type) {
            DialogPeerType.PRIVATE -> {
                val user = requireNotNull(userStore?.get(scope, peer.id)) { "Missing MTProto user projection: ${peer.id}" }
                InputPeerUser(peer.id, requireNotNull(user.accessHash) { "Missing MTProto user access hash: ${peer.id}" })
            }
            DialogPeerType.BASIC_GROUP -> InputPeerChat(peer.id)
            DialogPeerType.SUPERGROUP, DialogPeerType.CHANNEL -> {
                val chat = requireNotNull(chatStore?.get(scope, peer.id)) { "Missing MTProto chat projection: ${peer.id}" }
                InputPeerChannel(peer.id, requireNotNull(chat.accessHash) { "Missing MTProto chat access hash: ${peer.id}" })
            }
            DialogPeerType.UNKNOWN -> error("Cannot load history for an unknown peer type")
        }
    }

    private fun DialogPeerType.toMtProtoPeerType() = when (this) {
        DialogPeerType.PRIVATE -> MtProtoMessagePeerType.USER
        DialogPeerType.BASIC_GROUP -> MtProtoMessagePeerType.GROUP
        DialogPeerType.SUPERGROUP,
        DialogPeerType.CHANNEL -> MtProtoMessagePeerType.CHANNEL
        DialogPeerType.UNKNOWN -> error("Cannot load history for an unknown peer type")
    }

    private fun MtProtoMessageReadModel.toDomain() = MessageHistorySnapshotModel(
        messageId = messageId.toLong(),
        senderId = senderId,
        date = date,
        text = text,
        isService = isService,
        isDeleted = isDeleted,
        isOutgoing = isOutgoing,
        isMentioned = isMentioned,
        isMediaUnread = isMediaUnread,
        isSilent = isSilent,
        isPinned = isPinned,
        editDate = editDate,
        groupedId = groupedId,
        hasMedia = hasMedia,
        documentId = documentId,
        photoId = photoId,
    )

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }

    private fun Long.toMtProtoMessageId(): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "MTProto history cursor message id is out of range"
        }
        return toInt()
    }
}
