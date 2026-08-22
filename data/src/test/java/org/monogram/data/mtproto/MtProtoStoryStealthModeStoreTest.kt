package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.mtproto.tl.generated.cloud.layer223.StoriesStealthMode_9a2f11feb7

class MtProtoStoryStealthModeStoreTest {
    private val scope = MtProtoAuthKeyScope("account_a", MtProtoEnvironment.PRODUCTION, 2)

    @Test
    fun `persists exact server stealth dates per scope`() = runBlocking {
        val store = KeyValueMtProtoStoryStealthModeStore(FakeKeyValueDao())

        store.save(scope, StoriesStealthMode_9a2f11feb7(100, 200))

        assertEquals(MtProtoStoryStealthMode(100, 200), store.get(scope))
        assertNull(store.get(scope.copy(dcId = 3)))
    }

    @Test
    fun `deletes the exact scoped stealth state`() = runBlocking {
        val store = KeyValueMtProtoStoryStealthModeStore(FakeKeyValueDao())
        store.save(scope, StoriesStealthMode_9a2f11feb7(100, 200))

        store.deleteAccount("account_a", MtProtoEnvironment.PRODUCTION)

        assertNull(store.get(scope))
    }

    private class FakeKeyValueDao : KeyValueDao {
        private val values = mutableMapOf<String, KeyValueEntity>()

        override suspend fun getValue(key: String): KeyValueEntity? = values[key]
        override fun observeValue(key: String): Flow<KeyValueEntity?> = emptyFlow()
        override suspend fun insertValue(entity: KeyValueEntity) { values[entity.key] = entity }
        override suspend fun deleteValue(key: String) { values.remove(key) }
        override suspend fun deleteValuesWithPrefix(prefix: String) {
            values.keys.removeAll { it.startsWith(prefix) }
        }
    }
}
