package org.monogram.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.datasource.remote.UserRemoteDataSource
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.repository.UserRepository

class ContactEditRepositoryImplTest {

    @Test
    fun `upsertContact forwards explicit share phone flag`() = runTest {
        val remote = FakeUserRemoteDataSource()
        val repository = ContactEditRepositoryImpl(
            userRepository = FakeUserRepository(),
            userRemoteDataSource = remote
        )

        repository.upsertContact(
            user = UserModel(
                id = 7L,
                firstName = "Ada",
                lastName = "Lovelace",
                phoneNumber = "+123"
            ),
            sharePhoneNumber = false
        )

        assertEquals(7L, remote.addContactUserId)
        assertFalse(remote.addContactSharePhoneNumber)
        assertEquals("Ada", remote.addContact?.firstName)
        assertEquals("Lovelace", remote.addContact?.lastName)
    }

    @Test
    fun `setCloseFriend appends user id when enabling`() = runTest {
        val remote = FakeUserRemoteDataSource(closeFriendIds = longArrayOf(1L, 2L))
        val repository = ContactEditRepositoryImpl(
            userRepository = FakeUserRepository(),
            userRemoteDataSource = remote
        )

        repository.setCloseFriend(userId = 7L, isCloseFriend = true)

        assertArrayEquals(longArrayOf(1L, 2L, 7L), remote.recordedCloseFriendIds)
    }

    @Test
    fun `setCloseFriend removes user id when disabling`() = runTest {
        val remote = FakeUserRemoteDataSource(closeFriendIds = longArrayOf(1L, 7L, 2L))
        val repository = ContactEditRepositoryImpl(
            userRepository = FakeUserRepository(),
            userRemoteDataSource = remote
        )

        repository.setCloseFriend(userId = 7L, isCloseFriend = false)

        assertArrayEquals(longArrayOf(1L, 2L), remote.recordedCloseFriendIds)
    }

    @Test
    fun `getNeedPhoneNumberPrivacyException delegates to user full info`() = runTest {
        val repository = ContactEditRepositoryImpl(
            userRepository = FakeUserRepository(
                fullInfo = ChatFullInfoModel(needPhoneNumberPrivacyException = true)
            ),
            userRemoteDataSource = FakeUserRemoteDataSource()
        )

        assertTrue(repository.getNeedPhoneNumberPrivacyException(7L))
    }
}

private class FakeUserRepository(
    private val user: UserModel? = UserModel(id = 7L, firstName = "Ada"),
    private val fullInfo: ChatFullInfoModel? = null
) : UserRepository {
    override val currentUserFlow = MutableStateFlow<UserModel?>(null)
    override val anyUserUpdateFlow: Flow<Long> = emptyFlow()

    override suspend fun getMe(): UserModel = user ?: UserModel(0L, "Unknown")
    override suspend fun getUser(userId: Long): UserModel? = user
    override suspend fun getUserFullInfo(userId: Long): UserModel? = user
    override suspend fun refreshUserFullInfo(userId: Long) = Unit
    override suspend fun resolveUserChatFullInfo(userId: Long): ChatFullInfoModel? = fullInfo
    override fun getUserFlow(userId: Long): Flow<UserModel?> = emptyFlow()
    override fun logOut() = Unit
    override suspend fun getContacts(): List<UserModel> = emptyList()
    override suspend fun searchContacts(query: String): List<UserModel> = emptyList()
    override suspend fun addContact(user: UserModel) = Unit
    override suspend fun removeContact(userId: Long) = Unit
    override suspend fun setCachedSimCountryIso(iso: String?) = Unit
}

private class FakeUserRemoteDataSource(
    private val closeFriendIds: LongArray = longArrayOf()
) : UserRemoteDataSource {
    var addContactUserId: Long = 0L
    var addContact: TdApi.ImportedContact? = null
    var addContactSharePhoneNumber: Boolean = true
    var recordedCloseFriendIds: LongArray = longArrayOf()

    override suspend fun getUser(userId: Long): TdApi.User? = null
    override suspend fun getMe(): TdApi.User? = null
    override suspend fun getUserFullInfo(userId: Long): TdApi.UserFullInfo? = null
    override suspend fun getSupergroupFullInfo(supergroupId: Long): TdApi.SupergroupFullInfo? = null
    override suspend fun getBasicGroupFullInfo(basicGroupId: Long): TdApi.BasicGroupFullInfo? = null
    override suspend fun getSupergroup(supergroupId: Long): TdApi.Supergroup? = null
    override suspend fun getChat(chatId: Long): TdApi.Chat? = null
    override suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? = null
    override suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int,
        limit: Int
    ): TdApi.ChatPhotos? = null

    override suspend fun getContacts(): TdApi.Users? = null
    override suspend fun searchContacts(query: String): TdApi.Users? = null
    override suspend fun addContact(
        userId: Long,
        contact: TdApi.ImportedContact,
        sharePhoneNumber: Boolean
    ) {
        addContactUserId = userId
        addContact = contact
        addContactSharePhoneNumber = sharePhoneNumber
    }

    override suspend fun removeContacts(userIds: LongArray) = Unit
    override suspend fun getCloseFriendIds(): LongArray = closeFriendIds
    override suspend fun setCloseFriendIds(userIds: LongArray) {
        recordedCloseFriendIds = userIds
    }

    override suspend fun sharePhoneNumber(userId: Long) = Unit
    override suspend fun searchPublicChat(username: String): TdApi.Chat? = null
    override suspend fun getSimilarChatIds(chatId: Long): LongArray = longArrayOf()
    override suspend fun getChatMember(chatId: Long, userId: Long): TdApi.ChatMember? = null
    override suspend fun getSupergroupMembers(
        supergroupId: Long,
        filter: TdApi.SupergroupMembersFilter,
        offset: Int,
        limit: Int
    ): TdApi.ChatMembers? = null

    override suspend fun getBasicGroupMembers(basicGroupId: Long): TdApi.BasicGroupFullInfo? = null
    override suspend fun getPremiumState(): TdApi.PremiumState? = null
    override suspend fun getPremiumFeatures(source: TdApi.PremiumSource): TdApi.PremiumFeatures? =
        null

    override suspend fun setSponsoredMessagesEnabled(enabled: Boolean) = Unit
    override suspend fun getBotFullInfo(userId: Long): TdApi.UserFullInfo? = null
    override suspend fun getChatStatistics(chatId: Long, isDark: Boolean): TdApi.ChatStatistics? =
        null

    override suspend fun getChatRevenueStatistics(
        chatId: Long,
        isDark: Boolean
    ): TdApi.ChatRevenueStatistics? = null

    override suspend fun getStatisticsGraph(
        chatId: Long,
        token: String,
        x: Long
    ): TdApi.StatisticalGraph? = null

    override suspend fun logout() = Unit
    override suspend fun setName(firstName: String, lastName: String) = Unit
    override suspend fun setBio(bio: String) = Unit
    override suspend fun setUsername(username: String) = Unit
    override suspend fun setEmojiStatus(customEmojiId: Long?) = Unit
    override suspend fun setProfilePhoto(path: String) = Unit
    override suspend fun setBirthdate(birthdate: TdApi.Birthdate?) = Unit
    override suspend fun setPersonalChat(chatId: Long) = Unit
    override suspend fun setBusinessBio(bio: String) = Unit
    override suspend fun setBusinessLocation(location: TdApi.BusinessLocation?) = Unit
    override suspend fun setBusinessOpeningHours(hours: TdApi.BusinessOpeningHours?) = Unit
    override suspend fun toggleUsernameIsActive(username: String, isActive: Boolean) = Unit
    override suspend fun reorderActiveUsernames(usernames: Array<String>) = Unit
    override suspend fun setChatMemberStatus(
        chatId: Long,
        userId: Long,
        status: TdApi.ChatMemberStatus
    ) = Unit
}
