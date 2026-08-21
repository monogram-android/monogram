package org.monogram.data.mtproto

import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.ContactEditRepository
import org.monogram.domain.repository.UserRepository

internal class MtProtoContactEditRepository(
    private val users: UserRepository,
    private val mtProtoProfiles: MtProtoUserProfileReader,
    private val accountId: String = DEFAULT_ACCOUNT_ID,
) : ContactEditRepository {
    override suspend fun getContact(userId: Long): UserModel? = users.getUser(userId)?.takeIf(UserModel::isContact)

    override suspend fun getNeedPhoneNumberPrivacyException(userId: Long): Boolean =
        mtProtoProfiles.getNeedPhoneNumberPrivacyException(accountId, userId)

    override suspend fun upsertContact(user: UserModel, sharePhoneNumber: Boolean): UserModel? {
        mtProtoProfiles.addContact(accountId, user.toProfileSnapshot(), sharePhoneNumber)
        return users.getUser(user.id)
    }

    override suspend fun removeContact(userId: Long) = mtProtoProfiles.removeContact(accountId, userId)

    override suspend fun setCloseFriend(userId: Long, isCloseFriend: Boolean): UserModel? {
        mtProtoProfiles.setCloseFriend(accountId, userId, isCloseFriend)
        return users.getUser(userId)
    }

    private fun UserModel.toProfileSnapshot() = UserProfileSnapshotModel(
        userId = id,
        firstName = firstName,
        lastName = lastName,
        username = username,
        phoneNumber = phoneNumber,
        isCurrentUser = false,
        isContact = isContact,
        isMutualContact = isMutualContact,
        isDeleted = type == org.monogram.domain.models.UserTypeEnum.DELETED,
        isBot = type == org.monogram.domain.models.UserTypeEnum.BOT,
        isVerified = isVerified,
        isRestricted = false,
        isScam = isScam,
        isFake = isFake,
        isPremium = isPremium,
        isPartial = false,
    )

    private companion object {
        const val DEFAULT_ACCOUNT_ID = "default"
    }
}
