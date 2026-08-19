package org.monogram.data.backend

import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.UserProfileSnapshotRepository
import org.monogram.domain.repository.UserRepository

internal class LegacyUserProfileSnapshotRepository(
    private val accessGuard: LegacyBackendAccessGuard,
    private val userRepository: UserRepository,
) : UserProfileSnapshotRepository {
    override suspend fun getCurrentUser(accountId: String): UserProfileSnapshotModel? {
        accessGuard.requireAccess(accountId)
        return userRepository.getMe().takeIf { it.id > 0L }?.toSnapshot(isCurrentUser = true)
    }

    override suspend fun getUser(accountId: String, userId: Long): UserProfileSnapshotModel? {
        accessGuard.requireAccess(accountId)
        if (userId <= 0L) return null
        val user = userRepository.getUser(userId) ?: return null
        return user.toSnapshot(isCurrentUser = userRepository.currentUserFlow.value?.id == user.id)
    }

    private fun UserModel.toSnapshot(isCurrentUser: Boolean) = UserProfileSnapshotModel(
        userId = id,
        firstName = firstName,
        lastName = lastName,
        username = username,
        phoneNumber = phoneNumber,
        isCurrentUser = isCurrentUser,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isDeleted = type == UserTypeEnum.DELETED,
        isBot = type == UserTypeEnum.BOT,
        isVerified = isVerified,
        isRestricted = restrictionInfo != null,
        isScam = isScam,
        isFake = isFake,
        isPremium = isPremium,
        isPartial = false,
    )
}
