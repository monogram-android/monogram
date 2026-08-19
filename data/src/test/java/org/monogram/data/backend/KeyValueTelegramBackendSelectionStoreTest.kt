package org.monogram.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

class KeyValueTelegramBackendSelectionStoreTest {
    @Test
    fun `defaults to legacy and persists accounts independently`() = runBlocking {
        val store = KeyValueTelegramBackendSelectionStore(InMemoryKeyValueDao())

        assertEquals(TelegramBackendKind.LEGACY, store.get("account_a"))

        store.select("account_a", TelegramBackendKind.KOTLIN_MTPROTO)

        assertEquals(TelegramBackendKind.KOTLIN_MTPROTO, store.get("account_a"))
        assertEquals(TelegramBackendKind.LEGACY, store.get("account_b"))
    }

    @Test
    fun `observation follows selection and reset returns legacy`() = runBlocking {
        val store = KeyValueTelegramBackendSelectionStore(InMemoryKeyValueDao())

        store.select("default", TelegramBackendKind.KOTLIN_MTPROTO)
        assertEquals(TelegramBackendKind.KOTLIN_MTPROTO, store.observe("default").first())

        store.reset("default")
        assertEquals(TelegramBackendKind.LEGACY, store.observe("default").first())
    }

    @Test
    fun `unknown persisted value fails closed to legacy`() = runBlocking {
        val dao = InMemoryKeyValueDao()
        dao.insertValue(KeyValueEntity("telegram_backend_v1_default", "UNKNOWN"))
        val store = KeyValueTelegramBackendSelectionStore(dao)

        assertEquals(TelegramBackendKind.LEGACY, store.get("default"))
    }

    @Test
    fun `rejects invalid account ids before storage access`() {
        val store = KeyValueTelegramBackendSelectionStore(InMemoryKeyValueDao())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.get("../other-account") }
        }
    }

    private class InMemoryKeyValueDao : KeyValueDao {
        private val values = mutableMapOf<String, KeyValueEntity>()
        private val flows = mutableMapOf<String, MutableStateFlow<KeyValueEntity?>>()

        override suspend fun getValue(key: String): KeyValueEntity? = values[key]

        override fun observeValue(key: String): Flow<KeyValueEntity?> =
            flows.getOrPut(key) { MutableStateFlow(values[key]) }

        override suspend fun insertValue(entity: KeyValueEntity) {
            values[entity.key] = entity
            flows.getOrPut(entity.key) { MutableStateFlow(null) }.value = entity
        }

        override suspend fun deleteValue(key: String) {
            values.remove(key)
            flows.getOrPut(key) { MutableStateFlow(null) }.value = null
        }
    }
}
