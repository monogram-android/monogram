package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "text_composition_styles")
data class TextCompositionStyleEntity(
    @PrimaryKey val name: String,
    val customEmojiId: Long,
    val title: String
)
