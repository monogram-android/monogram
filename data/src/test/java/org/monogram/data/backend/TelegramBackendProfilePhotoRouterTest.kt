package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.monogram.domain.models.ProfilePhotoMedia
import org.monogram.domain.repository.ProfilePhotoRepository
import org.junit.Test

class TelegramBackendProfilePhotoRouterTest {
    @Test
    fun `selected MTProto profile photos fail closed without creating legacy repository`() = runBlocking {
        val router = TelegramBackendProfilePhotoRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy profile photo repository must not be created") },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val failure = runCatching { router.getUserProfilePhotos(userId = 7) }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }

    @Test
    fun `selected MTProto chat profile photos avoid legacy repository`() = runBlocking {
        val router = TelegramBackendProfilePhotoRouter(
            selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
            legacyFactory = { error("legacy profile photo repository must not be created") },
            mtProtoFactory = { object : ProfilePhotoRepository {
                override suspend fun getUserProfilePhotos(userId: Long, offset: Int, limit: Int) = emptyList<ProfilePhotoMedia>()
                override suspend fun getChatProfilePhotos(chatId: Long, offset: Int, limit: Int) = listOf(
                    ProfilePhotoMedia(id = 1, previewPath = null, originalFileId = 2, originalPath = null),
                )
                override fun getUserProfilePhotosFlow(userId: Long): Flow<List<ProfilePhotoMedia>> = kotlinx.coroutines.flow.emptyFlow()
                override fun getChatProfilePhotosFlow(chatId: Long): Flow<List<ProfilePhotoMedia>> = kotlinx.coroutines.flow.emptyFlow()
            } },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(1L, router.getChatProfilePhotos(-7).single().id)
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
