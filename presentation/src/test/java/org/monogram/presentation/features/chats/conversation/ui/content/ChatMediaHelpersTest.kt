package org.monogram.presentation.features.chats.conversation.ui.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.domain.models.MessageContent
import org.monogram.domain.models.MessageModel

class ChatMediaHelpersTest {
    @Test
    fun `photo display path prefers original path over thumbnail`() {
        val message = message(
            MessageContent.Photo(
                path = "/photo.jpg",
                thumbnailPath = "/thumb.jpg",
                width = 100,
                height = 80
            )
        )

        assertEquals("/photo.jpg", message.displayMediaPath { true })
    }

    @Test
    fun `photo display path falls back to thumbnail`() {
        val message = message(
            MessageContent.Photo(
                path = null,
                thumbnailPath = "/thumb.jpg",
                width = 100,
                height = 80
            )
        )

        assertEquals("/thumb.jpg", message.displayMediaPath { it == "/thumb.jpg" })
    }

    @Test
    fun `video and gif expose local display paths`() {
        val video = message(
            MessageContent.Video(
                path = "/video.mp4",
                width = 1280,
                height = 720,
                duration = 10
            )
        )
        val gif = message(
            MessageContent.Gif(
                path = "/anim.gif",
                width = 320,
                height = 240
            )
        )

        assertEquals("/video.mp4", video.displayMediaPath { true })
        assertEquals("/anim.gif", gif.displayMediaPath { true })
    }

    @Test
    fun `missing media path is not displayed`() {
        val message = message(
            MessageContent.Video(
                path = "/missing.mp4",
                width = 1280,
                height = 720,
                duration = 10
            )
        )

        assertNull(message.displayMediaPath { false })
    }

    @Test
    fun `album entries keep mixed photo video order captions and ids`() {
        val photo = message(
            content = MessageContent.Photo(
                path = "/photo.jpg",
                width = 100,
                height = 80,
                caption = "photo"
            ),
            id = 1L
        )
        val video = message(
            content = MessageContent.Video(
                path = "/video.mp4",
                width = 1280,
                height = 720,
                duration = 10,
                caption = "video"
            ),
            id = 2L
        )
        val text = message(MessageContent.Text("skip"), id = 3L)

        val entries = buildAlbumMediaEntries(listOf(photo, video, text)) { true }

        assertEquals(listOf(1L, 2L), entries.map { it.message.id })
        assertEquals(listOf("/photo.jpg", "/video.mp4"), entries.map { it.path })
        assertEquals(listOf("photo", "video"), entries.map { it.caption })
    }

    @Test
    fun `audio voice and documents do not become album media entries`() {
        val audio = message(
            MessageContent.Audio(
                path = "/audio.mp3",
                duration = 5,
                title = "title",
                performer = "artist",
                fileName = "audio.mp3",
                mimeType = "audio/mpeg",
                size = 10
            )
        )
        val document = message(
            MessageContent.Document(
                path = "/file.pdf",
                fileName = "file.pdf",
                mimeType = "application/pdf",
                size = 10
            )
        )

        assertEquals(
            emptyList<AlbumMediaEntry>(),
            buildAlbumMediaEntries(listOf(audio, document)) { true })
    }

    private fun message(content: MessageContent, id: Long = 1L): MessageModel =
        MessageModel(
            id = id,
            date = 1,
            isOutgoing = false,
            senderName = "sender",
            chatId = 1L,
            content = content
        )
}
