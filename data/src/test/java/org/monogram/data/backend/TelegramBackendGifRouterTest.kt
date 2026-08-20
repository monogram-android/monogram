package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.models.GifModel
import org.monogram.domain.repository.GifRepository
import kotlinx.coroutines.flow.emptyFlow

class TelegramBackendGifRouterTest {
    @Test
    fun `selected MTProto GIF reads avoid creating legacy repository`() = runBlocking {
        val expected = GifModel("gif", null, 1L, null, 0, 0)
        val router = TelegramBackendGifRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy GIF repository must not be created") },
            mtProtoFactory = {
                object : GifRepository {
                    override fun getGifFile(gif: GifModel) = emptyFlow<String?>()
                    override fun getGifThumbnailFile(fileId: Long) = emptyFlow<String?>()
                    override suspend fun getSavedGifs() = listOf(expected)
                    override suspend fun addSavedGif(path: String) = throw UnsupportedOperationException()
                    override suspend fun searchGifs(query: String) = throw UnsupportedOperationException()
                }
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(listOf(expected), router.getSavedGifs())
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
