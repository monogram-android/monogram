package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "date"]),
        Index(value = ["chatId", "id"]),
        Index(value = ["createdAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: Long,
    val chatId: Long,
    val senderId: Long,
    val senderName: String = "",
    val content: String,
    val contentType: String = "text",
    val contentMeta: String? = null,
    val mediaFileId: Int = 0,
    val mediaPath: String? = null,
    val date: Int,
    val isOutgoing: Boolean,
    val isRead: Boolean,
    val replyToMessageId: Long = 0L,
    val replyToPreview: String? = null,
    val replyToPreviewType: String? = null,
    val replyToPreviewText: String? = null,
    val replyToPreviewSenderName: String? = null,
    val replyCount: Int = 0,
    val forwardFromName: String? = null,
    val forwardFromId: Long = 0L,
    val forwardOriginChatId: Long? = null,
    val forwardOriginMessageId: Long? = null,
    val forwardDate: Int = 0,
    val editDate: Int = 0,
    val mediaAlbumId: Long = 0L,
    val entities: String? = null,
    val viewCount: Int = 0,
    val forwardCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)