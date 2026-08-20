package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoAccountStateResetter
import org.monogram.data.mtproto.MtProtoAuthSessionResetter
import org.monogram.data.mtproto.MtProtoLiveSessionResetter
import org.monogram.data.mtproto.MtProtoUserProfileReader
import org.monogram.domain.models.UserModel
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.domain.repository.UserRepository

class TelegramBackendUserRouterTest {
    @Test
    fun `MTProto current user does not construct legacy user repository`() = runBlocking {
        val router = TelegramBackendUserRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy user repository must not be created") },
            mtProtoProfiles = object : MtProtoUserProfileReader {
                override suspend fun getCurrentUser(accountId: String) = profile(42)
                override suspend fun getUser(accountId: String, userId: Long) = profile(userId)
                override suspend fun getContacts(accountId: String) = listOf(profile(43))
                override suspend fun addContact(accountId: String, user: UserProfileSnapshotModel) { addedId = user.userId }
                override suspend fun removeContact(accountId: String, userId: Long) { removedId = userId }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            mtProtoAccountStateResetter = MtProtoAccountStateResetter { _, _ -> resetCalls++ },
            mtProtoAuthSessionResetter = MtProtoAuthSessionResetter { resetCalls++ },
            mtProtoLiveSessionResetter = MtProtoLiveSessionResetter { resetCalls++ },
        )

        val user = router.getMe()

        assertEquals(42L, user.id)
        assertEquals("Ada", user.firstName)
        assertEquals(user, router.currentUserFlow.value)
        assertEquals(listOf(43L), router.getContacts().map { it.id })
        assertEquals(listOf(43L), router.searchContacts("ADA").map { it.id })
        router.setCachedSimCountryIso("US")
        router.addContact(UserModel(id = 43, firstName = "Ada"))
        router.removeContact(43)
        assertEquals(43L, addedId)
        assertEquals(43L, removedId)
        router.logOut()
        assertEquals(3, resetCalls)
    }

    private var resetCalls = 0
    private var addedId: Long? = null
    private var removedId: Long? = null

    private fun profile(id: Long) = UserProfileSnapshotModel(
        userId = id,
        firstName = "Ada",
        lastName = "Lovelace",
        username = "ada",
        phoneNumber = null,
        isCurrentUser = id == 42L,
        isContact = false,
        isMutualContact = false,
        isDeleted = false,
        isBot = false,
        isVerified = true,
        isRestricted = false,
        isScam = false,
        isFake = false,
        isPremium = true,
        isPartial = false,
    )

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
