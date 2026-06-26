package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow

interface PinnedMessageVisibilityRepository {
    fun observeHidden(chatId: Long): Flow<Boolean>
    suspend fun isHidden(chatId: Long): Boolean
    suspend fun hide(chatId: Long)
    suspend fun show(chatId: Long)
}
