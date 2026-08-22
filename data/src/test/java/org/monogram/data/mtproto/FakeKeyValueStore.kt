package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

/** In-memory [KeyValueDao] for unit tests. */
class FakeKeyValueStore : KeyValueDao {
    private val values = LinkedHashMap<String, KeyValueEntity>()

    override suspend fun getValue(key: String): KeyValueEntity? = values[key]

    override fun observeValue(key: String): Flow<KeyValueEntity?> = flow { emit(values[key]) }

    override suspend fun insertValue(entity: KeyValueEntity) {
        values[entity.key] = entity
    }

    override suspend fun deleteValue(key: String) {
        values.remove(key)
    }

    override suspend fun deleteValuesWithPrefix(prefix: String) {
        values.keys.removeAll { it.startsWith(prefix) }
    }
}
