package org.monogram.data.mtproto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.datasource.cache.StickerLocalDataSource
import org.monogram.domain.models.RecentEmojiModel
import org.monogram.domain.models.StickerSetModel
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.AvailableReactionsNotModified
import org.monogram.mtproto.tl.generated.cloud.layer223.messages.GetAvailableReactions
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoEmojiRepositoryTest {
    @Test
    fun `uses owned available reactions request and falls back when unchanged`() = runTest {
        val transport = RecordingTransport()
        val repository = MtProtoEmojiRepository(
            context = null,
            localDataSource = NoOpStickerLocalDataSource,
            transportFactory = MtProtoSessionTransportFactory { transport },
            fallbackEmojis = { listOf("😀") },
        )

        assertEquals(listOf("😀"), repository.getDefaultEmojis())
        assertEquals(0, (transport.requests.single() as GetAvailableReactions).hash)
        assertTrue(transport.closed)
    }

    private class RecordingTransport : MtProtoRpcTransport {
        val requests = mutableListOf<TlMethod<*>>()
        var closed = false
        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            requests += method
            return AvailableReactionsNotModified as R
        }
        override fun close() { closed = true }
    }

    private object NoOpStickerLocalDataSource : StickerLocalDataSource {
        override fun getInstalledStickerSetsByType(type: String): Flow<List<StickerSetModel>> = emptyFlow()
        override fun getArchivedStickerSetsByType(type: String): Flow<List<StickerSetModel>> = emptyFlow()
        override suspend fun getStickerSetById(id: Long): StickerSetModel? = null
        override suspend fun getStickerSetByName(name: String): StickerSetModel? = null
        override suspend fun saveStickerSets(sets: List<StickerSetModel>, type: String, isInstalled: Boolean, isArchived: Boolean) = Unit
        override suspend fun insertStickerSet(set: StickerSetModel, type: String) = Unit
        override suspend fun clearStickerSets() = Unit
        override suspend fun getPath(fileId: Long): String? = null
        override suspend fun insertPath(fileId: Long, path: String) = Unit
        override suspend fun deletePath(fileId: Long) = Unit
        override suspend fun clearPaths() = Unit
        override fun getRecentEmojis(): Flow<List<RecentEmojiModel>> = emptyFlow()
        override suspend fun addRecentEmoji(recentEmoji: RecentEmojiModel) = Unit
        override suspend fun clearRecentEmojis() = Unit
    }
}
