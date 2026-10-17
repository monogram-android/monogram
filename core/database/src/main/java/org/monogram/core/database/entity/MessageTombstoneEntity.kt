package org.monogram.core.database.entity

import androidx.room.Entity

@Entity(tableName = "message_tombstones", primaryKeys = ["chatId", "id"])
data class MessageTombstoneEntity(val chatId: Long, val id: Int)
