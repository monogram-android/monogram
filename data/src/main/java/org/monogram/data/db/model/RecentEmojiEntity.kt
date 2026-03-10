package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_emojis")
data class RecentEmojiEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val emoji: String,
    val stickerId: Long?,
    val data: String,
    val timestamp: Long = System.currentTimeMillis()
)
