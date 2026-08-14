package org.monogram.presentation.features.chats.conversation.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.MessageContent

class MessageDownloadStatePolicyTest {

    @Test
    fun `queued progress marks every downloadable message type as downloading`() {
        downloadableContent().forEach { content ->
            val queued = content.withFileDownloadState(FILE_ID, isDownloading = true, progress = 0f)
            val state = queued.downloadState()

            assertTrue(queued::class.simpleName, state.isDownloading)
            assertEquals(queued::class.simpleName, 0f, state.progress)
            assertFalse(queued::class.simpleName, state.hasError)
        }
    }

    @Test
    fun `cancel clears queued state for every downloadable message type`() {
        downloadableContent().forEach { content ->
            val cancelled = content
                .withFileDownloadState(FILE_ID, isDownloading = true, progress = 0.4f)
                .withFileDownloadState(FILE_ID, isDownloading = false, progress = 0f)
            val state = cancelled.downloadState()

            assertFalse(cancelled::class.simpleName, state.isDownloading)
            assertEquals(cancelled::class.simpleName, 0f, state.progress)
        }
    }

    @Test
    fun `download event for another file leaves media unchanged`() {
        downloadableContent().forEach { content ->
            assertSame(
                content::class.simpleName,
                content,
                content.withFileDownloadState(FILE_ID + 1, isDownloading = true, progress = 0f)
            )
        }
    }

    private fun downloadableContent(): List<MessageContent> = listOf(
        MessageContent.Photo(
            path = null,
            width = 100,
            height = 100,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.Video(
            path = null,
            width = 100,
            height = 100,
            duration = 1,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.VideoNote(
            path = null,
            thumbnail = null,
            duration = 1,
            length = 100,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.Document(
            path = null,
            fileName = "file.bin",
            mimeType = "application/octet-stream",
            size = 10,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.Audio(
            path = null,
            duration = 1,
            title = "Audio",
            performer = "Artist",
            fileName = "audio.mp3",
            mimeType = "audio/mpeg",
            size = 10,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.Gif(
            path = null,
            width = 100,
            height = 100,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.Voice(
            path = null,
            duration = 1,
            downloadError = true,
            fileId = FILE_ID
        ),
        MessageContent.Sticker(
            id = 1,
            setId = 1,
            path = null,
            width = 100,
            height = 100,
            downloadError = true,
            fileId = FILE_ID
        )
    )

    private fun MessageContent.downloadState(): DownloadState = when (this) {
        is MessageContent.Photo -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.Video -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.VideoNote -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.Document -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.Audio -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.Gif -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.Voice -> DownloadState(isDownloading, downloadProgress, downloadError)
        is MessageContent.Sticker -> DownloadState(isDownloading, downloadProgress, downloadError)
        else -> error("Unsupported test content ${this::class.simpleName}")
    }

    private data class DownloadState(
        val isDownloading: Boolean,
        val progress: Float,
        val hasError: Boolean
    )

    private companion object {
        const val FILE_ID = 42
    }
}
