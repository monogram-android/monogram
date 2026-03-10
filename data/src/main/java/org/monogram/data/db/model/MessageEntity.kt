package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: Long,
    val chatId: Long,
    val senderId: Long,
    val content: String,
    val date: Int,
    val isOutgoing: Boolean,
    val isRead: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)