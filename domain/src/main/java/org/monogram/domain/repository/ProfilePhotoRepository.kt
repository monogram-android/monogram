package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow

interface ProfilePhotoRepository {
    suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int = 0,
        limit: Int = 10,
        ensureFullRes: Boolean = false
    ): List<String>

    suspend fun getChatProfilePhotos(
        chatId: Long,
        offset: Int = 0,
        limit: Int = 10,
        ensureFullRes: Boolean = false
    ): List<String>

    fun getUserProfilePhotosFlow(userId: Long): Flow<List<String>>
    fun getChatProfilePhotosFlow(chatId: Long): Flow<List<String>>
}
