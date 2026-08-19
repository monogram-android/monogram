package org.monogram.data.mtproto

import org.monogram.domain.models.DialogPeerType
import org.monogram.domain.models.MessageHistoryCursorModel
import org.monogram.domain.models.MessageHistorySnapshotModel
import org.monogram.domain.models.MessageHistorySnapshotPage
import org.monogram.domain.models.MessageHistorySnapshotRequest
import org.monogram.domain.repository.MessageHistorySnapshotRepository

internal class MtProtoMessageHistorySnapshotRepository(
    private val configSource: TelegramMtProtoBootstrapConfigSource,
    private val messageStore: MtProtoMessageProjectionStore,
) : MessageHistorySnapshotRepository {
    override suspend fun getHistory(request: MessageHistorySnapshotRequest): MessageHistorySnapshotPage {
        require(request.limit in 1..MAX_PAGE_SIZE) { "History page size must be between 1 and $MAX_PAGE_SIZE" }
        val config = configSource.create()
        val scope = MtProtoAuthKeyScope(request.accountId, MtProtoEnvironment.PRODUCTION, config.endpoint.dcId)
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
