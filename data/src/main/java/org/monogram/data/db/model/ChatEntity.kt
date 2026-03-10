package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val unreadCount: Int,
    val avatarPath: String?,
    val lastMessageText: String,
    val lastMessageTime: String,
    val order: Long,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isChannel: Boolean,
    val isGroup: Boolean,
    val type: String,
    val isArchived: Boolean,
    val memberCount: Int,
    val onlineCount: Int,
    val createdAt: Long = System.currentTimeMillis()
)