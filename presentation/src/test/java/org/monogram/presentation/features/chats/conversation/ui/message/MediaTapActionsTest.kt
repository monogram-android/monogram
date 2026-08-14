package org.monogram.presentation.features.chats.conversation.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel
import org.monogram.presentation.features.chats.conversation.ui.content.VideoTapAction
import org.monogram.presentation.features.chats.conversation.ui.content.handleVideoTap
import org.monogram.presentation.features.chats.conversation.ui.content.resolveVideoTapAction

class MediaTapActionsTest {

    @Test
    fun `sticker without path resolves to download`() {
        val action = resolveStickerTapAction(path = null)

        assertEquals(StickerTapAction.Download, action)
    }

    @Test
    fun `sticker with path resolves to open set`() {
        val action = resolveStickerTapAction(path = "/tmp/sticker.webp") { true }

        assertEquals(StickerTapAction.OpenSet, action)
    }

    @Test
    fun `non streamable video without path resolves to download`() {
        val action = resolveVideoTapAction(path = null, supportsStreaming = false)

        assertEquals(VideoTapAction.Download, action)
    }

    @Test
    fun `streamable video without path resolves to open`() {
        val action = resolveVideoTapAction(path = null, supportsStreaming = true)

        assertEquals(VideoTapAction.Open, action)
    }

    @Test
    fun `video with local path resolves to open`() {
        val action = resolveVideoTapAction(path = "/tmp/video.mp4", supportsStreaming = false)

        assertEquals(VideoTapAction.Open, action)
    }

    @Test
    fun `video note without path requests its file download`() {
        var downloadedFileId = 0

        handleVideoTap(
            msg = videoNoteMessage(path = null),
            onDownloadVideo = { downloadedFileId = it },
            onVideoClick = { _, _, _ -> error("Video note without a path must not open") }
        )

        assertEquals(FILE_ID, downloadedFileId)
    }

    @Test
    fun `downloaded video note opens its local path`() {
        var openedPath: String? = null

        handleVideoTap(
            msg = videoNoteMessage(path = VIDEO_NOTE_PATH),
            onDownloadVideo = { error("Downloaded video note must not enqueue again") },
            onVideoClick = { _, path, _ -> openedPath = path }
        )

        assertEquals(VIDEO_NOTE_PATH, openedPath)
    }

    private fun videoNoteMessage(path: String?) = MessageModel(
        id = 1,
        date = 1,
        isOutgoing = false,
        senderName = "sender",
        chatId = 1,
        content = MessageContent.VideoNote(
            path = path,
            thumbnail = null,
            duration = 1,
            length = 100,
            fileId = FILE_ID
        )
    )

    private companion object {
        const val FILE_ID = 73
        const val VIDEO_NOTE_PATH = "/tmp/video-note.mp4"
    }
}
