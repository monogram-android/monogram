package org.monogram.data.db.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_full_info")
data class UserFullInfoEntity(
    @PrimaryKey val userId: Long,
    val bio: String?,
    val commonGroupsCount: Int,
    val isBlocked: Boolean,
    val canBeCalled: Boolean,
    val supportsVideoCalls: Boolean,
    val hasPrivateCalls: Boolean,
    val hasPrivateForwards: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)