package org.monogram.data.infra

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.DispatcherProvider
import org.monogram.data.chats.ChatCache
import org.monogram.data.gateway.TelegramGateway

@OptIn(ExperimentalCoroutinesApi::class)
class FileDownloadQueueTest {

    @Test
    fun `cancel pending download notifies observer without waiting for update file`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = createQueue(dispatcher, this)
        val cancelled = mutableListOf<Int>()
        queue.setObserver(object : FileDownloadQueue.Observer {
            override fun onDownloadCancelled(fileId: Int) {
                cancelled += fileId
            }
        })
        queue.registry.register(fileId = 42, chatId = 1L, messageId = 2L)

        queue.enqueue(fileId = 42, priority = 1, type = FileDownloadQueue.DownloadType.DEFAULT)
        runCurrent()

        queue.cancelDownload(fileId = 42, force = true, suppress = false)
        runCurrent()

        assertEquals(listOf(42), cancelled)
        assertTrue(!queue.isFileQueued(42))
    }

    @Test
    fun `manual enqueue after suppression is accepted`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = createQueue(dispatcher, this)

        queue.cancelDownload(fileId = 73, force = true, suppress = true)
        runCurrent()

        queue.enqueue(
            fileId = 73,
            priority = 32,
            type = FileDownloadQueue.DownloadType.DEFAULT,
            ignoreSuppression = true
        )
        runCurrent()

        assertTrue(queue.isFileQueued(73))
    }

    private fun createQueue(
        dispatcher: CoroutineDispatcher,
        scope: TestScope
    ): FileDownloadQueue {
        return FileDownloadQueue(
            gateway = FakeTelegramGateway(),
            registry = FileMessageRegistry(),
            cache = ChatCache(),
            scope = scope.backgroundScope,
            dispatcherProvider = TestDispatcherProvider(dispatcher)
        )
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher
    ) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
        override val mainImmediate: CoroutineDispatcher = dispatcher
    }

    private class FakeTelegramGateway : TelegramGateway {
        override fun lane(
            name: String,
            scope: kotlinx.coroutines.CoroutineScope,
            context: kotlin.coroutines.CoroutineContext,
            filter: (TdApi.Update) -> Boolean,
            handler: suspend (TdApi.Update) -> Unit,
        ) = org.monogram.data.testing.fakeUpdateLane(updates, scope, context, filter, handler)

        override val updates = MutableSharedFlow<TdApi.Update>()
        override val isAuthenticated = MutableStateFlow(false)

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : TdApi.Object> execute(function: TdApi.Function<T>): T {
            return when (function) {
                is TdApi.CancelDownloadFile -> TdApi.Ok() as T
                is TdApi.GetFile -> TdApi.File().apply {
                    id = function.fileId
                    local = TdApi.LocalFile().apply {
                        path = ""
                        isDownloadingActive = false
                        isDownloadingCompleted = false
                    }
                    remote = TdApi.RemoteFile()
                } as T

                is TdApi.DownloadFile -> TdApi.File().apply {
                    id = function.fileId
                    local = TdApi.LocalFile().apply {
                        path = ""
                        isDownloadingActive = false
                        isDownloadingCompleted = false
                    }
                    remote = TdApi.RemoteFile()
                } as T

                else -> error("Unexpected function ${function.javaClass.simpleName}")
            }
        }
    }
}
