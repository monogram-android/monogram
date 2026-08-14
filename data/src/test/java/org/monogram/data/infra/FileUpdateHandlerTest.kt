package org.monogram.data.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.data.gateway.TelegramGateway
import org.monogram.data.gateway.UpdateDispatcherImpl
import org.monogram.data.testing.fakeUpdateLane
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class FileUpdateHandlerTest {

    @Test
    fun `burst file completions preserve every terminal path and order`() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val updates = MutableSharedFlow<TdApi.Update>()
        val queue = RecordingQueue()
        val registry = FileMessageRegistry()
        val handler = FileUpdateHandler(
            registry = registry,
            queue = queue,
            updates = UpdateDispatcherImpl(FakeTelegramGateway(updates)),
            scope = scope
        )
        val fileCompleted = mutableListOf<Pair<Long, String>>()
        val messageCompleted = mutableListOf<Pair<Long, String>>()
        val collectors = listOf(
            scope.launch {
                handler.fileDownloadCompleted.take(BURST_SIZE).collect(fileCompleted::add)
            },
            scope.launch {
                handler.downloadCompleted.take(BURST_SIZE).collect(messageCompleted::add)
            }
        )
        runCurrent()

        repeat(BURST_SIZE) { index ->
            val fileId = index + 1
            registry.register(fileId, CHAT_ID, (MESSAGE_ID_OFFSET + fileId).toLong())
            updates.emit(TdApi.UpdateFile(completedFile(fileId)))
        }
        advanceUntilIdle()

        assertEquals((1..BURST_SIZE).map { it.toLong() to "path_$it" }, fileCompleted)
        assertEquals(
            (1..BURST_SIZE).map { (MESSAGE_ID_OFFSET + it).toLong() to "path_$it" },
            messageCompleted
        )
        assertEquals((1..BURST_SIZE).toList(), queue.completedDownloads)
        collectors.forEach { it.cancel() }
        scope.cancel()
    }

    private fun completedFile(id: Int) = TdApi.File().apply {
        this.id = id
        size = 1
        local = TdApi.LocalFile().apply {
            path = "path_$id"
            isDownloadingCompleted = true
        }
        remote = TdApi.RemoteFile()
    }

    private class FakeTelegramGateway(
        override val updates: MutableSharedFlow<TdApi.Update>
    ) : TelegramGateway {
        override val isAuthenticated = MutableStateFlow(false)

        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            error("Not used")
        }

        override fun lane(
            name: String,
            scope: CoroutineScope,
            context: CoroutineContext,
            filter: (TdApi.Update) -> Boolean,
            handler: suspend (TdApi.Update) -> Unit,
        ) = fakeUpdateLane(updates, scope, context, filter, handler)
    }

    private class RecordingQueue : FileUpdateQueue {
        val completedDownloads = mutableListOf<Int>()

        override fun updateFileCache(file: TdApi.File) = Unit

        override fun getCachedFile(fileId: Int): TdApi.File? = null

        override fun notifyDownloadComplete(fileId: Int) {
            completedDownloads += fileId
        }

        override fun notifyUploadComplete(fileId: Int) = Unit
    }

    private companion object {
        const val BURST_SIZE = 256
        const val CHAT_ID = 100L
        const val MESSAGE_ID_OFFSET = 1_000
    }
}
