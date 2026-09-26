package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "update_cursors")
data class UpdateCursorEntity(
    @PrimaryKey val key: String,
    val pts: Int,
    val qts: Int,
    val date: Int,
    val seq: Int,
    val channelPts: Int = 0,
)
