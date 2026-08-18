package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoCloudObjectDao
import org.monogram.data.db.model.MtProtoCloudObjectEntity
import org.monogram.mtproto.codec.CloudTlObjectCodec
import org.monogram.mtproto.tl.generated.cloud.layer223.ChatEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.MessageEmpty
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined
import org.monogram.mtproto.tl.generated.cloud.layer223.UserEmpty
import org.monogram.mtproto.updates.MtProtoUpdateCursor
import org.monogram.mtproto.updates.MtProtoUpdateDifferenceBatch

class MtProtoRoomCloudObjectStagerTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.TEST, 4)

    @Test
    fun `stages typed difference objects once within account dc scope`() = runBlocking {
        val dao = FakeCloudObjectDao()
        val stager = MtProtoRoomCloudObjectStager(dao) { 1234L }
        val batch = MtProtoUpdateDifferenceBatch(
            newMessages = listOf(MessageEmpty(30, null)),
            newEncryptedMessages = emptyList(),
            otherUpdates = listOf(UpdateDeleteMessages(emptyList(), 11, 1)),
            chats = listOf(ChatEmpty(20)),
            users = listOf(UserEmpty(10)),
            cursor = MtProtoUpdateCursor(11, 20, 30, 40),
        )

        stager.stageDifference(scope, batch)
        stager.stageDifference(scope, batch)

        val stored = dao.getAll("account-1", "test", 4)
        assertEquals(listOf("user", "chat", "message", "update"), stored.map { it.objectType })
        assertTrue(stored.all { it.createdAt == 1234L })
        assertEquals(
            listOf(UserEmpty(10), ChatEmpty(20), MessageEmpty(30, null)),
            stored.take(3).map { CloudTlObjectCodec.decode(it.payload) },
        )
    }

    @Test
    fun `stages live envelope and deletes only selected account environment`() = runBlocking {
        val dao = FakeCloudObjectDao()
        val stager = MtProtoRoomCloudObjectStager(dao)
        val envelope = UpdatesCombined(
            updates = listOf(UpdateDeleteMessages(emptyList(), 11, 1)),
            users = emptyList(),
            chats = emptyList(),
            date = 31,
            seqStart = 41,
            seq = 41,
        )

        stager.stageLive(scope, envelope)
        stager.stageLive(scope.copy(environment = MtProtoEnvironment.PRODUCTION), envelope)
        stager.deleteAccount("account-1", MtProtoEnvironment.TEST)

        assertTrue(dao.getAll("account-1", "test", 4).isEmpty())
        assertEquals(
            listOf("update"),
            dao.getAll("account-1", "prod", 4).map { it.objectType },
        )
    }

    private class FakeCloudObjectDao : MtProtoCloudObjectDao {
        private val entities = mutableListOf<MtProtoCloudObjectEntity>()
        private var nextId = 1L

        override suspend fun insertAll(objects: List<MtProtoCloudObjectEntity>): List<Long> = objects.map { entity ->
            val existing = entities.firstOrNull {
                it.accountSlot == entity.accountSlot &&
                    it.environment == entity.environment &&
                    it.dcId == entity.dcId &&
                    it.objectType == entity.objectType &&
                    it.payloadHash == entity.payloadHash
            }
            if (existing != null) {
                -1L
            } else {
                val stored = entity.copy(sequenceId = nextId++)
                entities += stored
                stored.sequenceId
            }
        }

        override suspend fun getAll(accountSlot: String, environment: String, dcId: Int) =
            entities.filter {
                it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId
            }.sortedBy { it.sequenceId }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }
    }
}
