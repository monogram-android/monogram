package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_settings")
data class NotificationSettingEntity(
    @PrimaryKey val chatId: Long,
    val muteFor: Int,
    val useDefault: Boolean
)
