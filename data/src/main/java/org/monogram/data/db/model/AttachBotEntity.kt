package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attach_bots")
data class AttachBotEntity(
    @PrimaryKey val botUserId: Long,
    val data: String
)
