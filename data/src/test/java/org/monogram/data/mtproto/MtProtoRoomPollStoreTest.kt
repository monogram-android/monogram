package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.monogram.data.db.model.MtProtoPollEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MtProtoRoomPollStoreTest {
    private val chatId = 9

    private class FakeDao : org.monogram.data.db.dao.MtProtoPollDao {
        var entity: org.monogram.data.db.model.MtProtoPollEntity? = null
        override suspend fun get(accountSlot: String, environment: String, pollId: Long) = entity
            ?.takeIf { it.accountSlot == accountSlot && it.pollId == pollId }
        override suspend fun upsert(entity: MtProtoPollEntity) {
            this.entity = entity
        }
        override suspend fun deleteAccount(accountSlot: String, environment: String) { this.entity = null }
    }

    @Test
    fun `round trips payload with voter counts and chosen flags`() = runBlocking {
        val dao = FakeDao()
        val store = MtProtoRoomPollStore(dao)

        store.upsert(
            pollId = 555L,
            question = "Favorite color?",
            optionLabels = listOf("Red", "Blue"),
            totalVoters = 30,
            isClosed = false,
            isAnonymous = true,
            voterCountsByOption = mapOf(
                byteArrayOf(1).toList() to MtProtoPollVoterInfo(12, true),
                byteArrayOf(2).toList() to MtProtoPollVoterInfo(18, false),
            ),
        )

        // Direct lookup by id returns stored fields.
        val payload = store.get(555L)
        assertEquals(555L, payload?.pollId)
        assertEquals(listOf("Red", "Blue"), payload?.options)
        assertEquals(30, payload?.totalVoters)
    }

    @Test
    fun `returns null for unknown polls`() = runBlocking {
        val store = MtProtoRoomPollStore(FakeDao())
        assertNull(store.get(404L))
    }
}
