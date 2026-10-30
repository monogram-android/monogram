package org.monogram.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index

// MessageEntity schema changes must migrate this table as well as messages.
@Entity(
    tableName = "scheduled_messages",
    primaryKeys = ["chatId", "id"],
    indices = [Index(value = ["chatId", "date"])]
)
data class ScheduledMessageEntity(
    @Embedded val message: MessageEntity,
    @androidx.room.ColumnInfo(defaultValue = "1") val hasUnsupportedContent: Boolean = true,
)

@Entity(tableName = "scheduled_queue_revisions", primaryKeys = ["chatId"])
data class ScheduledQueueRevisionEntity(val chatId: Long, val revision: Long)
