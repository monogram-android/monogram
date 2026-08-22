package org.monogram.data.mtproto

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.MtProtoSecretChatStateDao
import org.monogram.data.db.model.MtProtoSecretChatStateEntity

class MtProtoRoomSecretChatStateStoreTest {
    private class FakeDao : MtProtoSecretChatStateDao {
        val entities = mutableListOf<MtProtoSecretChatStateEntity>()

        override suspend fun get(accountSlot: String, environment: String, chatId: Int) =
            entities.firstOrNull { it.accountSlot == accountSlot && it.chatId == chatId }

        override suspend fun upsert(entity: MtProtoSecretChatStateEntity) {
            entities.removeAll { it.accountSlot == entity.accountSlot && it.chatId == entity.chatId }
            entities += entity
        }

        override suspend fun delete(accountSlot: String, environment: String, chatId: Int) {
            entities.removeAll { it.accountSlot == accountSlot && it.chatId == chatId }
        }

        override suspend fun deleteAccount(accountSlot: String, environment: String) {
            entities.removeAll { it.accountSlot == accountSlot }
        }
    }

    private fun seededEntity(chatId: Int = 9) = MtProtoSecretChatStateEntity(
        accountSlot = "default",
        environment = "production",
        chatId = chatId,
        accessHash = 8L,
        adminId = 1L,
        participantId = 2L,
        authKey = ByteArray(256) { (it * 3).toByte() },
        keyFingerprint = 12345678901234L,
        maxInSeq = 4,
        maxOutSeq = 6,
        updatedAt = 0L,
    )

    @Test
    fun `recordSend advances out counter and use accounting`() = runBlocking {
        val dao = FakeDao()
        dao.upsert(seededEntity())
        val store = MtProtoRoomSecretChatStateStore(dao)

        assertTrue(store.recordSend(9))

        val state = store.get(9)!!
        assertEquals(7, state.maxOutSeq)
        assertEquals(1, state.keyUseCountOut)
        assertEquals(4, state.maxInSeq)
    }

    @Test
    fun `stage and commit swap the future key atomically`() = runBlocking {
        val dao = FakeDao()
        dao.upsert(seededEntity())
        val store = MtProtoRoomSecretChatStateStore(dao)

        val futureKey = ByteArray(256) { (it * 5).toByte() }
        val fingerprint = MessageDigest.getInstance("SHA-1").digest(futureKey)
            .let { sha1 ->
                var value = 0L
                for (byte in sha1.copyOfRange(sha1.size - 8, sha1.size)) value = (value shl 8) or (byte.toLong() and 0xFF)
                value
            }
        store.stageFutureKey(9, futureKey, fingerprint)

        var state = store.get(9)!!
        assertEquals(futureKey.toList(), state.futureAuthKey!!.toList())
        assertEquals(fingerprint, state.futureKeyFingerprint)
        // Active key unchanged until commit.
        assertTrue(state.authKey.contentEquals(seededEntity().authKey))

        store.commitFutureKey(9)

        state = store.get(9)!!
        assertEquals(futureKey.toList(), state.authKey.toList())
        assertEquals(fingerprint, state.keyFingerprint)
        assertNull(state.futureAuthKey)
        assertEquals(0L, state.exchangeId)
        // Use counters reset with the fresh key.
        assertEquals(0, state.keyUseCountIn)
        assertEquals(0, state.keyUseCountOut)
    }

    @Test
    fun `commit without a staged key is a no-op`() = runBlocking {
        val dao = FakeDao()
        dao.upsert(seededEntity())
        val store = MtProtoRoomSecretChatStateStore(dao)

        store.commitFutureKey(9)

        val state = store.get(9)!!
        assertTrue(state.authKey.contentEquals(seededEntity().authKey))
        assertEquals(0L, state.exchangeId)
    }

    @Test
    fun `missing chats report send failure`() = runBlocking {
        val store = MtProtoRoomSecretChatStateStore(FakeDao())
        assertFalse(store.recordSend(404))
    }
}
