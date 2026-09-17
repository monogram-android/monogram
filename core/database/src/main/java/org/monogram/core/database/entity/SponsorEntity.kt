package org.monogram.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sponsors")
data class SponsorEntity(
    @PrimaryKey val userId: Long,
    val sourceChannelId: Long,
    val updatedAt: Long,
)
