package org.monogram.data.mapper

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.data.datasource.remote.MessageFileApi
import org.monogram.data.datasource.remote.TdMessageRemoteDataSource.DownloadType
import org.monogram.data.infra.FileDownloadQueue

class MappedMediaDemandCoordinatorTest {
    @Test
    fun `registers mapped video descriptors without enqueueing downloads`() {
        val fileApi = RecordingMessageFileApi()
        val coordinator = MappedMediaDemandCoordinator(fileApi)
        val message = TdApi.Message().apply {
            id = 20L
            chatId = 10L
            content = TdApi.MessageVideo().apply {
                video = TdApi.Video().apply {
                    video = file(7)
                    thumbnail = TdApi.Thumbnail().apply { file = file(8) }
                }
            }
        }

        coordinator.register(message)

        assertEquals(
            listOf(
                Registration(
                    7,
                    10L,
                    20L,
                    DownloadType.VIDEO,
                    FileDownloadQueue.MediaDescriptor(
                        kind = FileDownloadQueue.MediaKind.VIDEO,
                        role = FileDownloadQueue.DemandRole.PRIMARY,
                        size = 0L
                    )
                ),
                Registration(
                    8,
                    10L,
                    20L,
                    DownloadType.DEFAULT,
                    FileDownloadQueue.MediaDescriptor(
                        kind = FileDownloadQueue.MediaKind.PHOTO,
                        role = FileDownloadQueue.DemandRole.PREVIEW,
                        size = 0L
                    )
                )
            ),
            fileApi.registrations
        )
        assertTrue(fileApi.enqueued.isEmpty())
    }

    private fun file(id: Int) = TdApi.File().apply { this.id = id }

    private data class Registration(
        val fileId: Int,
        val chatId: Long,
        val messageId: Long,
        val type: DownloadType,
        val descriptor: FileDownloadQueue.MediaDescriptor?
    )

    private class RecordingMessageFileApi : MessageFileApi {
        val registrations = mutableListOf<Registration>()
        val enqueued = mutableListOf<Int>()

        override fun registerFileForMessage(fileId: Int, chatId: Long, messageId: Long) = Unit

        override fun registerFileForMessage(
            fileId: Int,
            chatId: Long,
            messageId: Long,
            type: DownloadType,
            descriptor: FileDownloadQueue.MediaDescriptor?
        ) {
            registrations += Registration(fileId, chatId, messageId, type, descriptor)
        }

        override fun registerSponsoredFileForMessage(fileId: Int, chatId: Long, messageId: Long) =
            Unit

        override fun enqueueDownload(
            fileId: Int,
            priority: Int,
            type: DownloadType,
            offset: Long,
            limit: Long,
            synchronous: Boolean
        ) {
            enqueued += fileId
        }

        override fun isFileQueued(fileId: Int): Boolean = false
    }
}
