package org.monogram.data.db.model

import androidx.room.Entity

@Entity(tableName = "topics", primaryKeys = ["chatId", "id"])
data class TopicEntity(
    val chatId: Long,
    val id: Int,
    val name: String,
    val iconCustomEmojiId: Long,
    val iconColor: Int,
    val isClosed: Boolean,
    val isPinned: Boolean,
    val unreadCount: Int,
    val lastMessageText: String,
    val lastMessageTime: String,
    val order: Long,
    val lastMessageSenderName: String?,
    val createdAt: Long = System.currentTimeMillis()
)