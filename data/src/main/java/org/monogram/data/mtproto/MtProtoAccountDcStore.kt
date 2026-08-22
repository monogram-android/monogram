package org.monogram.data.mtproto

import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

internal interface MtProtoAccountDcStore {
    suspend fun get(accountSlot: String): Int?
    suspend fun save(accountSlot: String, dcId: Int)
    suspend fun delete(accountSlot: String)
}

internal object NoOpMtProtoAccountDcStore : MtProtoAccountDcStore {
    override suspend fun get(accountSlot: String): Int? = null
    override suspend fun save(accountSlot: String, dcId: Int) = Unit
    override suspend fun delete(accountSlot: String) = Unit
}

internal class KeyValueMtProtoAccountDcStore(
    private val keyValueDao: KeyValueDao,
) : MtProtoAccountDcStore {
    override suspend fun get(accountSlot: String): Int? =
        keyValueDao.getValue(key(accountSlot))?.value?.toIntOrNull()?.takeIf { it > 0 }

    override suspend fun save(accountSlot: String, dcId: Int) {
        require(dcId > 0) { "dcId must be positive" }
        keyValueDao.insertValue(KeyValueEntity(key(accountSlot), dcId.toString()))
    }

    override suspend fun delete(accountSlot: String) {
        keyValueDao.deleteValue(key(accountSlot))
    }

    private fun key(accountSlot: String): String {
        require(ACCOUNT_SLOT.matches(accountSlot)) { "Invalid MTProto account slot" }
        return "$KEY_PREFIX$accountSlot"
    }

    private companion object {
        const val KEY_PREFIX = "mtproto_home_dc_v1_"
        val ACCOUNT_SLOT = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
