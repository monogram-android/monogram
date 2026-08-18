package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.models.UserProfileSnapshotModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig

class MtProtoUserProfileSnapshotRepositoryTest {
    @Test
    fun `maps current user projection without exposing backend fields`() = runBlocking {
        val store = RecordingUserStore(profile())
        val repository = MtProtoUserProfileSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 4) },
            userStore = store,
        )

        val profile = repository.getCurrentUser("account-1")

        assertEquals(MtProtoAuthKeyScope("account-1", MtProtoEnvironment.PRODUCTION, 4), store.selfScope)
        assertEquals(
            UserProfileSnapshotModel(
                userId = 10,
                firstName = "Alice",
                lastName = "Smith",
                username = "alice",
                phoneNumber = "123",
                isCurrentUser = true,
                isContact = true,
                isMutualContact = true,
                isDeleted = false,
                isBot = false,
                isVerified = true,
                isRestricted = false,
                isScam = false,
                isFake = false,
                isPremium = true,
                isPartial = false,
            ),
            profile,
        )
    }

    @Test
    fun `loads requested user in production scope and preserves absence`() = runBlocking {
        val store = RecordingUserStore(profile = null)
        val repository = MtProtoUserProfileSnapshotRepository(
            configSource = TelegramMtProtoBootstrapConfigSource { config(dcId = 2) },
            userStore = store,
        )

        val profile = repository.getUser("account-2", 30)

        assertNull(profile)
        assertEquals(MtProtoAuthKeyScope("account-2", MtProtoEnvironment.PRODUCTION, 2), store.userScope)
        assertEquals(30L, store.userId)
    }

    private fun profile() = MtProtoUserReadModel(
        userId = 10,
        accessHash = 99,
        firstName = "Alice",
        lastName = "Smith",
        username = "alice",
        phone = "123",
        isSelf = true,
        isContact = true,
        isMutualContact = true,
        isDeleted = false,
        isBot = false,
        isVerified = true,
        isRestricted = false,
        isScam = false,
        isFake = false,
        isPremium = true,
        isMin = false,
    )

    private fun config(dcId: Int) = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(dcId, "dc", 443),
        handshake = MtProtoHandshakeConfig(dcId, listOf("test-key")),
        cloud = CloudLayer223ConnectionConfig(
            apiId = 12345,
            deviceModel = "device",
            systemVersion = "system",
            applicationVersion = "app",
            systemLanguageCode = "en",
        ),
    )

    private class RecordingUserStore(
        private val profile: MtProtoUserReadModel?,
    ) : MtProtoUserProjectionStore by NoOpMtProtoUserProjectionStore {
        var selfScope: MtProtoAuthKeyScope? = null
        var userScope: MtProtoAuthKeyScope? = null
        var userId: Long? = null

        override suspend fun getSelf(scope: MtProtoAuthKeyScope): MtProtoUserReadModel? {
            selfScope = scope
            return profile
        }

        override suspend fun get(scope: MtProtoAuthKeyScope, userId: Long): MtProtoUserReadModel? {
            userScope = scope
            this.userId = userId
            return profile
        }
    }
}
