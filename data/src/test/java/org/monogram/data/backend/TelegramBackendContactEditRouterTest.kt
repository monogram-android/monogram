package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoUserProfileReader
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.UserRepository

class TelegramBackendContactEditRouterTest {
    @Test
    fun `selected MTProto contact edit avoids legacy and preserves share flag`() = runBlocking {
        var sharePhone = false
        val router = TelegramBackendContactEditRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy contact repository must not be created") },
            users = FakeUserRepository(),
            mtProtoProfiles = object : MtProtoUserProfileReader {
                override suspend fun getCurrentUser(accountId: String) = null
                override suspend fun getUser(accountId: String, userId: Long) = snapshot(userId)
                override suspend fun getContacts(accountId: String) = emptyList<UserProfileSnapshotModel>()
                override suspend fun addContact(accountId: String, user: UserProfileSnapshotModel, sharePhoneNumber: Boolean) {
                    sharePhone = sharePhoneNumber
                }
                override suspend fun removeContact(accountId: String, userId: Long) = Unit
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        router.upsertContact(UserModel(id = 7, firstName = "Ada"), sharePhoneNumber = false)

        assertEquals(false, sharePhone)
    }

    private fun snapshot(id: Long) = UserProfileSnapshotModel(
        userId = id,
        firstName = "Ada",
        lastName = null,
        username = "",
        phoneNumber = null,
        isCurrentUser = false,
        isContact = true,
        isMutualContact = false,
        isDeleted = false,
        isBot = false,
        isVerified = false,
        isRestricted = false,
        isScam = false,
        isFake = false,
        isPremium = false,
        isPartial = false,
    )

    private class FakeUserRepository : UserRepository {
        override val currentUserFlow = MutableStateFlow<UserModel?>(null)
        override val anyUserUpdateFlow: Flow<Long> = emptyFlow()
        override suspend fun getMe() = UserModel(id = 7, firstName = "Ada", lastName = null, username = "", phoneNumber = "")
        override suspend fun getUser(userId: Long) = UserModel(id = userId, firstName = "Ada", lastName = null, username = "", phoneNumber = "")
        override suspend fun getUserFullInfo(userId: Long) = getUser(userId)
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

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
