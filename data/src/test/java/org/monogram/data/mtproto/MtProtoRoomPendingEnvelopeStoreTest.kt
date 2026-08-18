package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoPendingEnvelopeDao
import org.monogram.data.db.model.MtProtoPendingEnvelopeEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateDeleteMessages
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdatesCombined

class MtProtoRoomPendingEnvelopeStoreTest {
    private val scope = MtProtoAuthKeyScope("account-1", MtProtoEnvironment.TEST, 4)

    @Test
    fun `round trips and deduplicates envelope within account dc scope`() = runBlocking {
        val dao = FakePendingEnvelopeDao()
        val store = MtProtoRoomPendingEnvelopeStore(dao) { 1234L }
        val envelope = envelope()

        val first = store.enqueue(scope, envelope)
        val duplicate = store.enqueue(scope, envelope)

        assertEquals(first.sequenceId, duplicate.sequenceId)
        assertEquals(1, dao.entities.size)
        assertEquals(1234L, dao.entities.single().createdAt)
        assertEquals(listOf(first), store.pending(scope))
    }

    @Test
    fun `reports corrupt payload and scopes account deletion by environment`() = runBlocking {
        val dao = FakePendingEnvelopeDao().apply {
            entities += entity(1, "account-1", "test", byteArrayOf(1, 2, 3, 4))
            entities += entity(2, "account-1", "production", byteArrayOf(1, 2, 3, 4))
        }
        val store = MtProtoRoomPendingEnvelopeStore(dao)

        assertEquals(listOf(MtProtoPendingEnvelope.Corrupt(1)), store.pending(scope))
        store.deleteAccount("account-1", MtProtoEnvironment.TEST)

        assertEquals(listOf(2L), dao.entities.map { it.sequenceId })
    }

    private fun envelope() = UpdatesCombined(
        updates = listOf(UpdateDeleteMessages(emptyList(), 11, 1)),
        users = emptyList(),
        chats = emptyList(),
        date = 31,
        seqStart = 41,
        seq = 41,
    )

    private fun entity(id: Long, account: String, environment: String, payload: ByteArray) =
        MtProtoPendingEnvelopeEntity(
            sequenceId = id,
            accountSlot = account,
            environment = environment,
            dcId = 4,
            payloadHash = "hash-$id",
            payload = payload,
            createdAt = 0,
        )

    private class FakePendingEnvelopeDao : MtProtoPendingEnvelopeDao {
        val entities = mutableListOf<MtProtoPendingEnvelopeEntity>()
        private var nextId = 1L

        override suspend fun insert(envelope: MtProtoPendingEnvelopeEntity): Long {
            val conflict = getByHash(
                envelope.accountSlot,
                envelope.environment,
                envelope.dcId,
                envelope.payloadHash,
            )
            if (conflict != null) return -1
            val stored = envelope.copy(sequenceId = nextId++)
            entities += stored
            return stored.sequenceId
        }

        override suspend fun getByHash(
            accountSlot: String,
            environment: String,
            dcId: Int,
            payloadHash: String,
        ) = entities.firstOrNull {
            it.accountSlot == accountSlot && it.environment == environment &&
                it.dcId == dcId && it.payloadHash == payloadHash
        }

        override suspend fun getPending(accountSlot: String, environment: String, dcId: Int) =
            entities.filter {
                it.accountSlot == accountSlot && it.environment == environment && it.dcId == dcId
            }.sortedBy { it.sequenceId }

        override suspend fun delete(sequenceId: Long) {
            assertTrue(entities.removeAll { it.sequenceId == sequenceId })
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot && it.environment == environment }
        }
    }
}
