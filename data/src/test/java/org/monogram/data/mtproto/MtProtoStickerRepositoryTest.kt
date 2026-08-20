package org.monogram.data.mtproto

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.domain.repository.StickerRepository

class MtProtoStickerRepositoryTest {
    @Test
    fun `serves persisted sticker file path locally`() = runBlocking {
        val repository = MtProtoStickerRepository(
            localDataSource = FakeStickerLocalDataSource(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals("/cache/sticker.webp", repository.getStickerFile(7L).first())
    }

    @Test
    fun `remote sticker refresh fails closed`() = runBlocking {
        val repository = MtProtoStickerRepository(
            localDataSource = FakeStickerLocalDataSource(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { repository.loadInstalledStickerSets() }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    private class FakeStickerLocalDataSource : StickerLocalDataSource {
        override fun getInstalledStickerSetsByType(type: String): Flow<List<StickerSetModel>> = emptyFlow()
        override fun getArchivedStickerSetsByType(type: String): Flow<List<StickerSetModel>> = emptyFlow()
        override suspend fun getStickerSetById(id: Long): StickerSetModel? = null
        override suspend fun getStickerSetByName(name: String): StickerSetModel? = null
        override suspend fun saveStickerSets(sets: List<StickerSetModel>, type: String, isInstalled: Boolean, isArchived: Boolean) = Unit
        override suspend fun insertStickerSet(set: StickerSetModel, type: String) = Unit
        override suspend fun clearStickerSets() = Unit
        override suspend fun getPath(fileId: Long): String? = "/cache/sticker.webp".takeIf { fileId == 7L }
        override suspend fun insertPath(fileId: Long, path: String) = Unit
        override suspend fun deletePath(fileId: Long) = Unit
        override suspend fun clearPaths() = Unit
        override fun getRecentEmojis(): Flow<List<RecentEmojiModel>> = flowOf(emptyList())
        override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) = Unit
        override suspend fun clearRecentEmojis() = Unit
    }
}
