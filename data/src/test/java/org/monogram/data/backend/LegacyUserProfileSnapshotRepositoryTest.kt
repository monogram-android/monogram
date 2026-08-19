package org.monogram.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.RestrictionInfoModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserTypeEnum
import org.monogram.domain.repository.UserRepository

class LegacyUserProfileSnapshotRepositoryTest {
    @Test
    fun `maps current legacy user after account guard`() = runBlocking {
        val user = UserModel(
            id = 10,
            firstName = "Alice",
            lastName = "Smith",
            username = "alice",
            phoneNumber = "123",
            isPremium = true,
            isVerified = true,
            isScam = true,
            isFake = true,
            isContact = true,
            isMutualContact = true,
            type = UserTypeEnum.BOT,
            restrictionInfo = RestrictionInfoModel("restricted"),
        )
        val repository = repository(FakeUserRepository(user))

        val profile = repository.getCurrentUser("default")

        assertEquals(10L, profile?.userId)
        assertEquals(true, profile?.isCurrentUser)
        assertEquals(true, profile?.isBot)
        assertEquals(true, profile?.isRestricted)
        assertEquals(false, profile?.isPartial)
    }

    @Test
    fun `rejects inactive account before touching legacy repository`() {
        val users = FakeUserRepository(UserModel(10, "Alice"))
        val repository = repository(users)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.getUser("other", 10) }
        }
        assertEquals(0, users.getUserCalls)
    }

    @Test
    fun `preserves missing and invalid requested users`() = runBlocking {
        val repository = repository(FakeUserRepository(null))

        assertNull(repository.getUser("default", 0))
        assertNull(repository.getUser("default", 20))
    }

    private fun repository(users: FakeUserRepository) = LegacyUserProfileSnapshotRepository(
        accessGuard = LegacyBackendAccessGuard(
            LegacyActiveAccountBinding(),
            FakeSelectionStore(),
        ),
        userRepository = users,
    )

    private class FakeSelectionStore : TelegramBackendSelectionStore {
        override suspend fun get(accountId: String) = TelegramBackendKind.LEGACY
        override fun observe(accountId: String) = error("not used")
        override suspend fun select(accountId: String, backend: TelegramBackendKind) = Unit
        override suspend fun reset(accountId: String) = Unit
    }

    private class FakeUserRepository(
        private val user: UserModel?,
    ) : UserRepository {
        override val currentUserFlow: StateFlow<UserModel?> = MutableStateFlow(user)
        override val anyUserUpdateFlow: Flow<Long> = emptyFlow()
        var getUserCalls = 0

        override suspend fun getMe() = user ?: UserModel(0, "Error")
        override suspend fun getUser(userId: Long): UserModel? {
            getUserCalls++
            return user
        }
        override suspend fun getUserFullInfo(userId: Long) = user
        override suspend fun refreshUserFullInfo(userId: Long) = Unit
        override suspend fun resolveUserChatFullInfo(userId: Long): ChatFullInfoModel? = null
        override fun getUserFlow(userId: Long): Flow<UserModel?> = emptyFlow()
        override fun logOut() = Unit
        override suspend fun getContacts() = emptyList<UserModel>()
        override suspend fun searchContacts(query: String) = emptyList<UserModel>()
        override suspend fun addContact(user: UserModel) = Unit
        override suspend fun removeContact(userId: Long) = Unit
        override suspend fun setCachedSimCountryIso(iso: String?) = Unit
    }
}
