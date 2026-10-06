package org.monogram.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "message_holes",
    primaryKeys = ["chatId", "startId", "endId", "source"],
)
data class MessageHoleEntity(
    val chatId: Long,
    val startId: Int,
    val endId: Int,
    val source: String = SOURCE_HISTORY,
) {
    companion object {
        const val SOURCE_HISTORY = "history"
    }
}
