package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: Long,
    val firstName: String,
    val lastName: String?,
    val phoneNumber: String?,
    val avatarPath: String?,
    val isPremium: Boolean,
    val isVerified: Boolean,
    val username: String?,
    val lastSeen: Long,
    val createdAt: Long = System.currentTimeMillis()
)