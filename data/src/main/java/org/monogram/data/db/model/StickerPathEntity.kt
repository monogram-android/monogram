package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sticker_paths")
data class StickerPathEntity(
    @PrimaryKey
    val fileId: Long,
    val path: String
)