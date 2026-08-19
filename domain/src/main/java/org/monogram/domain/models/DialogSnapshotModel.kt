package org.monogram.domain.models

data class DialogSnapshotModel(
    val peerId: Long,
    val peerType: DialogPeerType,
    val title: String?,
    val username: String?,
    val isPeerResolved: Boolean,
    val isPeerDeleted: Boolean,
    val isPeerForbidden: Boolean,
    val latestMessage: DialogMessagePreviewModel,
)

enum class DialogPeerType {
    PRIVATE,
    BASIC_GROUP,
    SUPERGROUP,
    CHANNEL,
    UNKNOWN,
}

data class DialogMessagePreviewModel(
    val messageId: Long,
    val senderId: Long?,
    val date: Int,
    val text: String?,
    val isService: Boolean,
    val isDeleted: Boolean,
    val isOutgoing: Boolean,
    val hasMedia: Boolean,
)
