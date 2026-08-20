package org.monogram.data.mtproto

import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

internal interface MtProtoAccountAuthorizationStore {
    suspend fun isAuthorized(accountSlot: String): Boolean
    suspend fun markAuthorized(accountSlot: String)
    suspend fun clear(accountSlot: String)
}

internal object NoOpMtProtoAccountAuthorizationStore : MtProtoAccountAuthorizationStore {
    override suspend fun isAuthorized(accountSlot: String): Boolean = false
    override suspend fun markAuthorized(accountSlot: String) = Unit
    override suspend fun clear(accountSlot: String) = Unit
}

internal class KeyValueMtProtoAccountAuthorizationStore(
    private val keyValueDao: KeyValueDao,
) : MtProtoAccountAuthorizationStore {
    override suspend fun isAuthorized(accountSlot: String): Boolean =
        keyValueDao.getValue(key(accountSlot))?.value == AUTHORIZED_VALUE

    override suspend fun markAuthorized(accountSlot: String) {
        keyValueDao.insertValue(KeyValueEntity(key(accountSlot), AUTHORIZED_VALUE))
    }

    override suspend fun clear(accountSlot: String) {
        keyValueDao.deleteValue(key(accountSlot))
    }

    private fun key(accountSlot: String): String {
        require(ACCOUNT_SLOT.matches(accountSlot)) { "Invalid MTProto account slot" }
        return "$KEY_PREFIX$accountSlot"
    }

    private companion object {
        const val KEY_PREFIX = "mtproto_authorized_v1_"
        const val AUTHORIZED_VALUE = "1"
        val ACCOUNT_SLOT = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
