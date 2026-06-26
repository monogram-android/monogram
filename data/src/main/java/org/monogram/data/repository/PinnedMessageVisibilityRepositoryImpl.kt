package org.monogram.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.monogram.data.db.dao.KeyValueDao
import org.monogram.data.db.model.KeyValueEntity
import org.monogram.domain.repository.PinnedMessageVisibilityRepository

class PinnedMessageVisibilityRepositoryImpl(
    private val keyValueDao: KeyValueDao
) : PinnedMessageVisibilityRepository {
    override fun observeHidden(chatId: Long): Flow<Boolean> =
        keyValueDao.observeValue(key(chatId))
            .map { it != null }
            .distinctUntilChanged()

    override suspend fun isHidden(chatId: Long): Boolean =
        keyValueDao.getValue(key(chatId)) != null

    override suspend fun hide(chatId: Long) {
        keyValueDao.insertValue(KeyValueEntity(key(chatId), "1"))
    }

    override suspend fun show(chatId: Long) {
        keyValueDao.deleteValue(key(chatId))
    }

    private fun key(chatId: Long): String = "$KEY_PREFIX$chatId"

    private companion object {
        const val KEY_PREFIX = "hidden_pinned_message_"
    }
}
