package org.monogram.data.backend

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.mtproto.MtProtoStreamingRepository
import org.monogram.data.mtproto.MtProtoFileRepository
import org.monogram.domain.models.FileDownloadEvent
import org.monogram.domain.models.FileModel
import org.monogram.domain.models.MessageDownloadEvent

class TelegramBackendStreamingRouterTest {
    @Test
    fun `selected MTProto streaming avoids legacy repository`() = runBlocking {
        val events = MutableSharedFlow<FileDownloadEvent>(replay = 1)
        val router = selectedRouter(events)

        events.tryEmit(FileDownloadEvent.Progress(1, 0.5f))

        assertEquals(0.5f, router.getDownloadProgress(1).first())
    }

    @Test
    fun `selected MTProto streaming maps terminal events`() = runBlocking {
        val events = MutableSharedFlow<FileDownloadEvent>(replay = 1)
        val router = selectedRouter(events)

        events.tryEmit(FileDownloadEvent.Completed(1, "/tmp/file"))
        assertEquals(1f, router.getDownloadProgress(1).first())

        events.tryEmit(FileDownloadEvent.Cancelled(1))
        assertEquals(0f, router.getDownloadProgress(1).first())
    }

    private fun selectedRouter(events: Flow<FileDownloadEvent>) = TelegramBackendStreamingRouter(
        selectionStore = FakeSelectionStore(TelegramBackendKind.KOTLIN_MTPROTO),
        legacyFactory = { error("legacy streaming repository must not be created") },
        mtProtoFactory = { MtProtoStreamingRepository(Files(events)) },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    private class Files(
        override val fileDownloadFlow: Flow<FileDownloadEvent>,
    ) : MtProtoFileRepository {
        override val messageDownloadFlow: Flow<MessageDownloadEvent> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun registerDocument(documentId: Long, chatId: Long, messageId: Long) = null
        override suspend fun registerDocument(documentId: Long) = null
        override suspend fun registerPhoto(photoId: Long, chatId: Long, messageId: Long) = null
        override fun download(fileId: Int, offset: Long, limit: Long) = Unit
        override suspend fun cancel(fileId: Int) = Unit
        override suspend fun getPath(fileId: Int) = null
        override suspend fun getInfo(fileId: Int): FileModel? = null
    }

    private class FakeSelectionStore(initial: TelegramBackendKind) : TelegramBackendSelectionStore {
        private val state = MutableStateFlow(initial)
        override suspend fun get(accountId: String) = state.value
        override fun observe(accountId: String): Flow<TelegramBackendKind> = state
        override suspend fun select(accountId: String, backend: TelegramBackendKind) { state.value = backend }
        override suspend fun reset(accountId: String) { state.value = TelegramBackendKind.LEGACY }
    }
}
