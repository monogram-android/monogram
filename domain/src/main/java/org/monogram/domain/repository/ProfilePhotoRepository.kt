package org.monogram.domain.repository

import kotlinx.coroutines.flow.Flow
import org.monogram.domain.models.ProfilePhotoMedia

interface ProfilePhotoRepository {
    suspend fun getUserProfilePhotos(
        userId: Long,
        offset: Int = 0,
        limit: Int = 10
    ): List<ProfilePhotoMedia>

    suspend fun getChatProfilePhotos(
        chatId: Long,
        offset: Int = 0,
        limit: Int = 10
    ): List<ProfilePhotoMedia>

    fun getUserProfilePhotosFlow(userId: Long): Flow<List<ProfilePhotoMedia>>
    fun getChatProfilePhotosFlow(chatId: Long): Flow<List<ProfilePhotoMedia>>
}
