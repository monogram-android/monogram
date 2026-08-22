package org.monogram.domain.models

data class UserProfileSnapshotModel(
    val userId: Long,
    val firstName: String?,
    val lastName: String?,
    val username: String?,
    val phoneNumber: String?,
    val isCurrentUser: Boolean,
    val isContact: Boolean,
    val isMutualContact: Boolean,
    val isDeleted: Boolean,
    val isBot: Boolean,
    val isVerified: Boolean,
    val isRestricted: Boolean,
    val isScam: Boolean,
    val isFake: Boolean,
    val isPremium: Boolean,
    val isPartial: Boolean,
)
