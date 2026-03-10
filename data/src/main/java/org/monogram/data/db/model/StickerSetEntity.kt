package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sticker_sets",
    indices = [Index(value = ["name"])]
)
data class StickerSetEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val type: String, // "REGULAR", "CUSTOM_EMOJI", "MASK"
    val isInstalled: Boolean,
    val isArchived: Boolean,
    val data: String,
    val createdAt: Long = System.currentTimeMillis()
)
