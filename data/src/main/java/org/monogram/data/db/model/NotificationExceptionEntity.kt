package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notification_exceptions",
    indices = [Index(value = ["scope"])]
)
data class NotificationExceptionEntity(
    @PrimaryKey val chatId: Long,
    val scope: String,
    val title: String,
    val avatarPath: String?,
    val personalAvatarPath: String?,
    val isMuted: Boolean,
    val isGroup: Boolean,
    val isChannel: Boolean,
    val type: String,
    val updatedAt: Long = System.currentTimeMillis()
)
