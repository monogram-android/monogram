package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.dao.MtProtoUserProjectionDao
import org.monogram.data.db.model.MtProtoCloudObjectEntity
import org.monogram.data.db.model.MtProtoUserProjectionEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.User_1990f29d1e

class MtProtoRoomUserProjectionStoreTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.TEST, 4)

    @Test
    fun `maps full user and min update preserves confirmed fields and flags`() = runBlocking {
        val store = MtProtoRoomUserProjectionStore(FakeUserProjectionDao(), nowMillis = { 1234L })
        store.upsert(
            scope,
            listOf(
                user(
                    id = 10,
                    accessHash = 99,
                    firstName = "Alice",
                    username = "alice",
                    contact = true,
                    verified = true,
                    premium = true,
                )
            ),
        )
        store.upsert(
            scope,
            listOf(user(id = 10, min = true, username = "alice_new")),
        )

        assertEquals(
            MtProtoUserReadModel(
                userId = 10,
                accessHash = 99,
                firstName = "Alice",
                lastName = null,
                username = "alice_new",
                phone = null,
                isSelf = false,
                isContact = true,
                isMutualContact = false,
                isDeleted = false,
                isBot = false,
                isVerified = true,
                isRestricted = false,
                isScam = false,
                isFake = false,
                isPremium = true,
                isMin = true,
            ),
            store.get(scope, 10),
        )
    }

    @Test
    fun `user empty replaces snapshot and account cleanup remains environment scoped`() = runBlocking {
        val dao = FakeUserProjectionDao()
        val store = MtProtoRoomUserProjectionStore(dao)
        store.upsert(scope, listOf(user(id = 10, firstName = "Alice")))
        store.upsert(scope, listOf(UserEmpty(10)))
        store.upsert(scope.copy(environment = MtProtoEnvironment.PRODUCTION), listOf(user(id = 20)))

        assertEquals(true, store.get(scope, 10)?.isDeleted)
        store.deleteAccount("account-1", MtProtoEnvironment.TEST)

        assertNull(store.get(scope, 10))
        assertEquals(listOf(20L), store.getAll(scope.copy(environment = MtProtoEnvironment.PRODUCTION)).map { it.userId })
    }

    @Test
    fun `backfills users from staged payloads and reports corrupt rows`() = runBlocking {
        val user = UserEmpty(30)
        val cloudDao = FakeCloudObjectDao(
            listOf(
                cloudEntity(1, CloudTlObjectCodec.encode(user)),
                cloudEntity(2, byteArrayOf(1, 2, 3, 4)),
            )
        )
        val store = MtProtoRoomUserProjectionStore(FakeUserProjectionDao(), cloudObjectDao = cloudDao)

        assertEquals(MtProtoUserProjectionBackfillResult(1, 1), store.backfill(scope))
        assertEquals(true, store.get(scope, 30)?.isDeleted)
    }

    @Test
    fun `current user lookup remains account environment and dc scoped`() = runBlocking {
        val store = MtProtoRoomUserProjectionStore(FakeUserProjectionDao())
        store.upsert(scope, listOf(user(id = 10, self = true, firstName = "Test")))
        store.upsert(
            scope.copy(environment = MtProtoEnvironment.PRODUCTION),
            listOf(user(id = 20, self = true, firstName = "Production")),
        )

        assertEquals(10L, store.getSelf(scope)?.userId)
        assertEquals(20L, store.getSelf(scope.copy(environment = MtProtoEnvironment.PRODUCTION))?.userId)
    }

    private fun user(
        id: Long,
        self: Boolean = false,
        accessHash: Long? = null,
        firstName: String? = null,
        username: String? = null,
        contact: Boolean = false,
        verified: Boolean = false,
        premium: Boolean = false,
        min: Boolean = false,
    ) = User_1990f29d1e(
        self = self,
        contact = contact,
        mutualContact = false,
        deleted = false,
        bot = false,
        botChatHistory = false,
        botNochats = false,
        verified = verified,
        restricted = false,
        min = min,
        botInlineGeo = false,
        support = false,
        scam = false,
        applyMinPhoto = false,
        fake = false,
        botAttachMenu = false,
        premium = premium,
        attachMenuEnabled = false,
        botCanEdit = false,
        closeFriend = false,
        storiesHidden = false,
        storiesUnavailable = false,
        contactRequirePremium = false,
        botBusiness = false,
        botHasMainApp = false,
        botForumView = false,
        botForumCanManageTopics = false,
        id = id,
        accessHash = accessHash,
        firstName = firstName,
        lastName = null,
        username = username,
        phone = null,
        photo = null,
        status = null,
        botInfoVersion = null,
        restrictionReason = null,
        botInlinePlaceholder = null,
        langCode = null,
        emojiStatus = null,
        usernames = null,
        storiesMaxId = null,
        color = null,
        profileColor = null,
        botActiveUsers = null,
        botVerificationIcon = null,
        sendPaidMessagesStars = null,
    )

    private class FakeUserProjectionDao : MtProtoUserProjectionDao {
        private val entities = mutableListOf<MtProtoUserProjectionEntity>()

        override suspend fun get(accountSlot: String, environment: String, dcId: Int, userId: Long) =
            entities.firstOrNull {
                it.accountSlot == accountSlot && it.environment == environment &&
                    it.dcId == dcId && it.userId == userId
            }

        override suspend fun getSelf(accountSlot: String, environment: String, dcId: Int) =
            entities.firstOrNull {
                it.accountSlot == accountSlot && it.environment == environment &&
                    it.dcId == dcId && it.isSelf
            }

        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) =
            entities.filter {
                it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId
            }.sortedBy { it.userId }

        override suspend fun upsert(entity: MtProtoUserProjectionEntity) {
            entities.removeAll {
                it.accountSlot == entity.accountSlot && it.environment == entity.environment &&
                    it.dcId == entity.dcId && it.userId == entity.userId
            }
            entities += entity
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }
    }

    private fun cloudEntity(id: Long, payload: ByteArray) = MtProtoCloudObjectEntity(
        sequenceId = id,
        accountSlot = "account-1",
        environment = "test",
        dcId = 4,
        objectType = "user",
        payloadHash = "hash-$id",
        payload = payload,
        createdAt = 0,
    )

    private class FakeCloudObjectDao(
        private val entities: List<MtProtoCloudObjectEntity>,
    ) : MtProtoCloudObjectDao {
        override suspend fun insertAll(objects: List<MtProtoCloudObjectEntity>) = emptyList<Long>()
        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) = entities
        override suspend fun getByType(
            accountSlot: String,
            environment: String,
            dcId: Int,
            objectType: String,
        ) = entities.filter { it.objectType == objectType }
        override suspend fun deleteAccount(accountSlot: String, environment: String) = Unit
    }
}
