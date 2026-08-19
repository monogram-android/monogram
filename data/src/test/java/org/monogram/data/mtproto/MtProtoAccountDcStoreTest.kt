package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

class MtProtoAccountDcStoreTest {
    @Test
    fun `persists and reads account home DC`() = runBlocking {
        val dao = FakeKeyValueDao()
        val store = KeyValueMtProtoAccountDcStore(dao)

        store.save("account_a", 5)

        assertEquals(5, store.get("account_a"))
        assertEquals(KeyValueEntity("mtproto_home_dc_v1_account_a", "5"), dao.values.single())
    }

    @Test
    fun `deletes account home DC`() = runBlocking {
        val dao = FakeKeyValueDao()
        val store = KeyValueMtProtoAccountDcStore(dao)
        store.save("account_a", 4)

        store.delete("account_a")

        assertNull(store.get("account_a"))
        assertEquals(listOf("mtproto_home_dc_v1_account_a"), dao.deletedKeys)
    }

    @Test
    fun `ignores malformed stored DC`() = runBlocking {
        val dao = FakeKeyValueDao()
        dao.insertValue(KeyValueEntity("mtproto_home_dc_v1_account_a", "invalid"))

        assertNull(KeyValueMtProtoAccountDcStore(dao).get("account_a"))
    }

    private class FakeKeyValueDao : KeyValueDao {
        val values = mutableListOf<KeyValueEntity>()
        val deletedKeys = mutableListOf<String>()

        override suspend fun getValue(key: String): KeyValueEntity? =
            values.firstOrNull { it.key == key }

        override fun observeValue(key: String): Flow<KeyValueEntity?> = emptyFlow()

        override suspend fun insertValue(entity: KeyValueEntity) {
            values.removeAll { it.key == entity.key }
            values += entity
        }

        override suspend fun deleteValue(key: String) {
            deletedKeys += key
            values.removeAll { it.key == key }
        }
    }
}
