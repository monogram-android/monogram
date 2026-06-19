package org.monogram.presentation.features.chats.conversation.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.presentation.features.chats.conversation.ui.content.VideoTapAction
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
}
