package org.monogram.data.backend

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity

internal enum class TelegramBackendKind {
    LEGACY,
    KOTLIN_MTPROTO,
}

internal interface TelegramBackendSelectionStore {
    suspend fun get(accountId: String): TelegramBackendKind
    fun observe(accountId: String): Flow<TelegramBackendKind>
    suspend fun select(accountId: String, backend: TelegramBackendKind)
    suspend fun reset(accountId: String)
}

internal class KeyValueTelegramBackendSelectionStore(
    private val keyValueDao: KeyValueDao,
) : TelegramBackendSelectionStore {
    override suspend fun get(accountId: String): TelegramBackendKind =
        keyValueDao.getValue(key(accountId))?.value.toBackendKind()

    override fun observe(accountId: String): Flow<TelegramBackendKind> =
        keyValueDao.observeValue(key(accountId)).map { it?.value.toBackendKind() }

    override suspend fun select(accountId: String, backend: TelegramBackendKind) {
        keyValueDao.insertValue(KeyValueEntity(key(accountId), backend.name))
    }

    override suspend fun reset(accountId: String) {
        keyValueDao.deleteValue(key(accountId))
    }

    private fun key(accountId: String): String {
        require(ACCOUNT_ID.matches(accountId)) { "Invalid Telegram account id" }
        return "$KEY_PREFIX$accountId"
    }

    private fun String?.toBackendKind(): TelegramBackendKind =
        runCatching { this?.let(TelegramBackendKind::valueOf) }
            .getOrNull()
            ?: TelegramBackendKind.LEGACY

    private companion object {
        const val KEY_PREFIX = "telegram_backend_v1_"
        val ACCOUNT_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
