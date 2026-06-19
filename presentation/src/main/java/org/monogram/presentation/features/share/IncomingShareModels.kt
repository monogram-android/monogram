package org.monogram.presentation.features.share

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
enum class PendingAttachmentKind : Parcelable {
    PHOTO,
    VIDEO,
    GIF,
    DOCUMENT;

    val isAlbumMedia: Boolean
        get() = this == PHOTO || this == VIDEO

    val isVisualMedia: Boolean
        get() = this == PHOTO || this == VIDEO || this == GIF
}

@Parcelize
@Serializable
data class PendingAttachment(
    val localPath: String,
    val kind: PendingAttachmentKind,
    val deleteAfterUse: Boolean = false
) : Parcelable

@Parcelize
@Serializable
data class IncomingShareRequest(
    val requestId: Long,
    val text: String = "",
    val attachments: List<PendingAttachment> = emptyList()
) : Parcelable

@Parcelize
@Serializable
data class ShareTarget(
    val chatId: Long,
    val topicId: Long? = null
) : Parcelable

val PendingAttachment.isDocument: Boolean
    get() = kind == PendingAttachmentKind.DOCUMENT

val PendingAttachment.isMedia: Boolean
    get() = !isDocument
