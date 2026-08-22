package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

class MtProtoAccountAuthorizationStoreTest {
    @Test
    fun `logout tombstone replaces authorization and successful authorization replaces tombstone`() = runBlocking {
        val dao = FakeKeyValueDao()
        val store = KeyValueMtProtoAccountAuthorizationStore(dao)

        store.markAuthorized("account_a")
        assertTrue(store.isAuthorized("account_a"))
        assertFalse(store.isLogoutPending("account_a"))

        store.markLogoutPending("account_a")
        assertFalse(store.isAuthorized("account_a"))
        assertTrue(store.isLogoutPending("account_a"))

        store.markAuthorized("account_a")
        assertTrue(store.isAuthorized("account_a"))
        assertFalse(store.isLogoutPending("account_a"))

        store.clear("account_a")
        assertFalse(store.isAuthorized("account_a"))
        assertFalse(store.isLogoutPending("account_a"))
    }

    private class FakeKeyValueDao : KeyValueDao {
        private val values = mutableMapOf<String, KeyValueEntity>()

        override suspend fun getValue(key: String): KeyValueEntity? = values[key]
        override fun observeValue(key: String): Flow<KeyValueEntity?> = emptyFlow()
        override suspend fun insertValue(entity: KeyValueEntity) { values[entity.key] = entity }
        override suspend fun deleteValue(key: String) { values.remove(key) }
        override suspend fun deleteValuesWithPrefix(prefix: String) {
            values.keys.removeIf { it.startsWith(prefix) }
        }
    }
}
