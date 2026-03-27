package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sponsors")
data class SponsorEntity(
    @PrimaryKey val userId: Long,
    val sourceChannelId: Long,
    val updatedAt: Long = System.currentTimeMillis()
)
