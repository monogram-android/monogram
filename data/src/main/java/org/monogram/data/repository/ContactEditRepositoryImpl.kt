package org.monogram.data.repository

import org.drinkless.tdlib.TdApi
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.ContactEditRepository
import org.monogram.domain.repository.UserRepository

class ContactEditRepositoryImpl(
    private val userRepository: UserRepository,
    private val userRemoteDataSource: UserRemoteDataSource
) : ContactEditRepository {

    override suspend fun getContact(userId: Long): UserModel? = userRepository.getUser(userId)

    override suspend fun getNeedPhoneNumberPrivacyException(userId: Long): Boolean {
        return userRepository.resolveUserChatFullInfo(userId)?.needPhoneNumberPrivacyException == true
    }

    override suspend fun upsertContact(
        user: UserModel,
        sharePhoneNumber: Boolean
    ): UserModel? {
        userRemoteDataSource.addContact(
            userId = user.id,
            contact = user.toImportedContact(),
            sharePhoneNumber = sharePhoneNumber
        )
        userRepository.refreshUserFullInfo(user.id)
        return userRepository.getUser(user.id)
    }

    override suspend fun removeContact(userId: Long) {
        userRemoteDataSource.removeContacts(longArrayOf(userId))
    }

    override suspend fun setCloseFriend(
        userId: Long,
        isCloseFriend: Boolean
    ): UserModel? {
        val closeFriends = userRemoteDataSource.getCloseFriendIds().toMutableSet()
        if (isCloseFriend) {
            closeFriends += userId
        } else {
            closeFriends -= userId
        }
        userRemoteDataSource.setCloseFriendIds(closeFriends.toLongArray())
        return userRepository.getUser(userId)
    }
}

private fun UserModel.toImportedContact() = TdApi.ImportedContact(
    phoneNumber.orEmpty(),
    firstName,
    lastName.orEmpty(),
    null
)
