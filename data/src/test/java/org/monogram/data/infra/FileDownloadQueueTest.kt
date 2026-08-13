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
import org.monogram.domain.repository.MediaAutoDownloadPolicy

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

    @Test
    fun `automatic sticker without open viewport demand is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = createQueue(dispatcher, this)
        queue.registry.register(fileId = 91, chatId = 1L, messageId = 2L)

        queue.enqueue(fileId = 91, type = FileDownloadQueue.DownloadType.STICKER)
        runCurrent()

        assertTrue(!queue.isFileQueued(91))
    }

    @Test
    fun `automatic sticker starts when message becomes visible in open chat`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = createQueue(dispatcher, this)
        queue.registerFileForMessage(
            fileId = 92,
            chatId = 1L,
            messageId = 2L,
            type = FileDownloadQueue.DownloadType.STICKER,
            descriptor = FileDownloadQueue.MediaDescriptor(
                kind = FileDownloadQueue.MediaKind.STICKER,
                role = FileDownloadQueue.DemandRole.PRIMARY,
                size = 1024L
            )
        )
        queue.setChatOpened(1L)
        queue.updateVisibleRange(
            chatId = 1L,
            visible = listOf(2L),
            nearby = emptyList(),
            policy = ENABLED_POLICY
        )

        queue.enqueue(fileId = 92, type = FileDownloadQueue.DownloadType.STICKER)
        runCurrent()

        assertTrue(queue.isFileQueued(92))
    }

    @Test
    fun `automatic downloads are capped at eight with two large media`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeTelegramGateway()
        val queue = createQueue(dispatcher, this, gateway)
        queue.setChatOpened(1L)

        (1..12).forEach { fileId ->
            val kind = if (fileId <= 4) {
                FileDownloadQueue.MediaKind.VIDEO
            } else {
                FileDownloadQueue.MediaKind.STICKER
            }
            queue.registerFileForMessage(
                fileId = fileId,
                chatId = 1L,
                messageId = fileId.toLong(),
                type = if (fileId <= 4) {
                    FileDownloadQueue.DownloadType.VIDEO
                } else {
                    FileDownloadQueue.DownloadType.STICKER
                },
                descriptor = FileDownloadQueue.MediaDescriptor(
                    kind = kind,
                    role = FileDownloadQueue.DemandRole.PRIMARY,
                    size = 1024L
                )
            )
        }
        queue.updateVisibleRange(
            chatId = 1L,
            visible = (1L..12L).toList(),
            nearby = emptyList(),
            policy = ENABLED_POLICY
        )
        (1..4).forEach { fileId ->
            queue.enqueue(fileId, type = FileDownloadQueue.DownloadType.VIDEO)
        }
        (5..12).forEach { fileId ->
            queue.enqueue(fileId, type = FileDownloadQueue.DownloadType.STICKER)
        }
        runCurrent()

        assertEquals(8, gateway.downloadCalls.size)
        assertTrue(gateway.downloadCalls.count { it.fileId in 1..4 } <= 2)
    }

    @Test
    fun `disabled policy does not start automatic visible download`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = createQueue(dispatcher, this)
        queue.registerFileForMessage(
            fileId = 93,
            chatId = 1L,
            messageId = 2L,
            type = FileDownloadQueue.DownloadType.STICKER,
            descriptor = FileDownloadQueue.MediaDescriptor(
                kind = FileDownloadQueue.MediaKind.STICKER,
                role = FileDownloadQueue.DemandRole.PRIMARY,
                size = 1024L
            )
        )
        queue.setChatOpened(1L)
        queue.updateVisibleRange(
            chatId = 1L,
            visible = listOf(2L),
            nearby = emptyList(),
            policy = MediaAutoDownloadPolicy.Disabled
        )
        runCurrent()

        assertTrue(!queue.isFileQueued(93))
    }

    @Test
    fun `nearby demand starts preview but not primary media`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeTelegramGateway()
        val queue = createQueue(dispatcher, this, gateway)
        queue.registerFileForMessage(
            94, 1L, 2L, FileDownloadQueue.DownloadType.VIDEO,
            descriptor(FileDownloadQueue.MediaKind.VIDEO)
        )
        queue.registerFileForMessage(
            95, 1L, 2L, FileDownloadQueue.DownloadType.DEFAULT,
            descriptor(FileDownloadQueue.MediaKind.PHOTO, FileDownloadQueue.DemandRole.PREVIEW)
        )
        queue.setChatOpened(1L)

        queue.updateVisibleRange(1L, emptyList(), listOf(2L), ENABLED_POLICY)
        runCurrent()

        assertEquals(listOf(95), gateway.downloadCalls.map { it.fileId })
    }

    @Test
    fun `streaming and unknown size primary media require manual demand`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeTelegramGateway()
        val queue = createQueue(dispatcher, this, gateway)
        queue.registerFileForMessage(
            96, 1L, 2L, FileDownloadQueue.DownloadType.VIDEO,
            descriptor(FileDownloadQueue.MediaKind.VIDEO, supportsStreaming = true)
        )
        queue.registerFileForMessage(
            97, 1L, 3L, FileDownloadQueue.DownloadType.DEFAULT,
            descriptor(FileDownloadQueue.MediaKind.DOCUMENT, size = 0L)
        )
        queue.setChatOpened(1L)
        queue.updateVisibleRange(1L, listOf(2L, 3L), emptyList(), ENABLED_POLICY)
        runCurrent()
        assertTrue(gateway.downloadCalls.isEmpty())

        queue.enqueue(96, priority = 32, userInitiated = true, ignoreSuppression = true)
        runCurrent()
        assertEquals(listOf(96), gateway.downloadCalls.map { it.fileId })
    }

    @Test
    fun `identical active demand is not resent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeTelegramGateway()
        val queue = createQueue(dispatcher, this, gateway)

        queue.enqueue(fileId = 100, priority = 32, userInitiated = true)
        runCurrent()
        queue.enqueue(fileId = 100, priority = 32, userInitiated = true)
        runCurrent()

        assertEquals(1, gateway.downloadCalls.count { it.fileId == 100 })
    }

    @Test
    fun `manual and speculative cancellation use different pending policy`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gateway = FakeTelegramGateway()
        val queue = createQueue(dispatcher, this, gateway)

        queue.cancelDownload(fileId = 201, force = false, suppress = false)
        queue.cancelDownload(fileId = 202, force = true, suppress = false)
        runCurrent()

        assertEquals(true, gateway.cancelCalls.single { it.fileId == 201 }.onlyIfPending)
        assertEquals(false, gateway.cancelCalls.single { it.fileId == 202 }.onlyIfPending)
    }

    private fun createQueue(
        dispatcher: CoroutineDispatcher,
        scope: TestScope,
        gateway: FakeTelegramGateway = FakeTelegramGateway()
    ): FileDownloadQueue {
        return FileDownloadQueue(
            gateway = gateway,
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
        val downloadCalls = mutableListOf<TdApi.DownloadFile>()
        val cancelCalls = mutableListOf<TdApi.CancelDownloadFile>()
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
                is TdApi.CancelDownloadFile -> {
                    cancelCalls += function
                    TdApi.Ok() as T
                }
                is TdApi.GetFile -> TdApi.File().apply {
                    id = function.fileId
                    local = TdApi.LocalFile().apply {
                        path = ""
                        isDownloadingActive = false
                        isDownloadingCompleted = false
                    }
                    remote = TdApi.RemoteFile()
                } as T

                is TdApi.DownloadFile -> {
                    downloadCalls += function
                    TdApi.File().apply {
                        id = function.fileId
                        local = TdApi.LocalFile().apply {
                            path = ""
                            isDownloadingActive = true
                            isDownloadingCompleted = false
                        }
                        remote = TdApi.RemoteFile()
                    } as T
                }

                else -> error("Unexpected function ${function.javaClass.simpleName}")
            }
        }
    }

    private fun descriptor(
        kind: FileDownloadQueue.MediaKind,
        role: FileDownloadQueue.DemandRole = FileDownloadQueue.DemandRole.PRIMARY,
        size: Long = 1024L,
        supportsStreaming: Boolean = false
    ) = FileDownloadQueue.MediaDescriptor(kind, role, size, supportsStreaming)

    private companion object {
        val ENABLED_POLICY = MediaAutoDownloadPolicy(enabled = true, allowFiles = false)
    }
}
