package org.monogram.domain.models

data class MessageHistorySnapshotRequest(
    val accountId: String,
    val peerType: DialogPeerType,
    val peerId: Long,
    val before: MessageHistoryCursorModel? = null,
    val limit: Int = 50,
)

data class MessageHistoryCursorModel(
    val date: Int,
    val messageId: Long,
)

data class MessageHistorySnapshotPage(
    val messages: List<MessageHistorySnapshotModel>,
    val nextCursor: MessageHistoryCursorModel?,
)

data class MessageHistorySnapshotModel(
    val messageId: Long,
    val senderId: Long?,
    val date: Int,
    val text: String?,
    val isService: Boolean,
    val isDeleted: Boolean,
    val isOutgoing: Boolean,
    val isMentioned: Boolean,
    val isMediaUnread: Boolean,
    val isSilent: Boolean,
    val isPinned: Boolean,
    val editDate: Int?,
    val groupedId: Long?,
    val hasMedia: Boolean,
    val documentId: Long? = null,
)
