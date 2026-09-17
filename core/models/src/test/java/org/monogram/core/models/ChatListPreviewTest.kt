package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListPreviewTest {
    @Test
    fun dmTextOnly() {
        assertEquals(
            "hello",
            formatChatListPreview(
                isGroup = false,
                isChannel = false,
                outgoing = false,
                senderName = "Mike",
                mediaKind = null,
                text = "hello",
            ),
        )
    }

    @Test
    fun dmMediaAndCaption() {
        assertEquals(
            "Photo, hello",
            formatChatListPreview(
                isGroup = false,
                isChannel = false,
                outgoing = false,
                senderName = "Mike",
                mediaKind = "photo",
                text = "hello",
            ),
        )
    }

    @Test
    fun groupIncomingMediaCaption() {
        assertEquals(
            "Mike: Photo, hello",
            formatChatListPreview(
                isGroup = true,
                isChannel = false,
                outgoing = false,
                senderName = "Mike",
                mediaKind = "photo",
                text = "hello",
            ),
        )
    }

    @Test
    fun groupOutgoingYou() {
        assertEquals(
            "You: sent",
            formatChatListPreview(
                isGroup = true,
                isChannel = false,
                outgoing = true,
                senderName = "Ada",
                mediaKind = null,
                text = "sent",
                youLabel = "You",
            ),
        )
    }

    @Test
    fun groupMediaOnly() {
        assertEquals(
            "Mike: Photo",
            formatChatListPreview(
                isGroup = true,
                isChannel = false,
                outgoing = false,
                senderName = "Mike",
                mediaKind = "photo",
                text = null,
            ),
        )
    }

    @Test
    fun displayPreviewFallsBackToStoredLine() {
        val chat = Chat(id = PeerId(1), title = "Ada", lastMessagePreview = "Mike: Photo, hi")
        assertEquals("Mike: Photo, hi", chat.displayPreview())
    }

    @Test
    fun displayPreviewUsesStructuredFields() {
        val chat = Chat(
            id = PeerId(1),
            title = "Group",
            isGroup = true,
            lastMessagePreview = "hello",
            lastMessageOutgoing = true,
            lastMessageMediaKind = "photo",
        )
        assertEquals("You: Photo, hello", chat.displayPreview())
    }

    @Test
    fun displayPreviewDoesNotRepeatYouOrPhoto() {
        val chat = Chat(
            id = PeerId(1),
            title = "Group",
            isGroup = true,
            lastMessagePreview = "You: Photo, hello",
            lastMessageOutgoing = true,
            lastMessageSenderName = "Ada",
            lastMessageMediaKind = "photo",
        )
        assertEquals("You: Photo, hello", chat.displayPreview())
    }

    @Test
    fun displayPreviewDoesNotRepeatPhotoOnChannelCaption() {
        val chat = Chat(
            id = PeerId(1),
            title = "News",
            isChannel = true,
            lastMessagePreview = "Photo, Li Auto presented",
            lastMessageMediaKind = "photo",
        )
        assertEquals("Photo, Li Auto presented", chat.displayPreview())
    }

    @Test
    fun displayPreviewShowsGroupSender() {
        val chat = Chat(
            id = PeerId(1),
            title = "Friends",
            isGroup = true,
            lastMessagePreview = "hello",
            lastMessageSenderName = "Mike",
            lastMessageMediaKind = null,
        )
        assertEquals("Mike: hello", chat.displayPreview())
    }

    @Test
    fun previewSourceKeepsCaptionsOverFileNames() {
        assertEquals(
            "look here",
            Message(
                id = MessageId(PeerId(1), 1),
                senderId = null,
                text = "look here",
                date = 0,
                outgoing = false,
                mediaKind = "sticker",
                fileName = "AnimatedSticker.tgs",
            ).chatListPreviewSource(),
        )
    }

    @Test
    fun previewSourceDropsStickerAndGifFileNames() {
        for (kind in listOf("sticker", "sticker_animated", "sticker_video", "gif", "photo")) {
            assertEquals(
                kind,
                null,
                Message(
                    id = MessageId(PeerId(1), 1),
                    senderId = null,
                    text = null,
                    date = 0,
                    outgoing = false,
                    mediaKind = kind,
                    fileName = "AnimatedSticker.tgs",
                ).chatListPreviewSource(),
            )
        }
        assertEquals(
            null,
            Message(
                id = MessageId(PeerId(1), 1),
                senderId = null,
                text = null,
                date = 0,
                outgoing = false,
                mediaKind = "document",
                fileName = "sticker.webm",
            ).chatListPreviewSource(),
        )
    }

    @Test
    fun previewSourceKeepsDocumentAndAudioNames() {
        assertEquals(
            "report.pdf",
            Message(
                id = MessageId(PeerId(1), 1),
                senderId = null,
                text = null,
                date = 0,
                outgoing = false,
                mediaKind = "document",
                fileName = "report.pdf",
            ).chatListPreviewSource(),
        )
        assertEquals(
            "Track — Artist",
            Message(
                id = MessageId(PeerId(1), 1),
                senderId = null,
                text = null,
                date = 0,
                outgoing = false,
                mediaKind = "audio",
                fileName = "Track — Artist",
            ).chatListPreviewSource(),
        )
    }

    @Test
    fun previewSourceKeepsUnknownMediaNames() {
        assertEquals(
            "holiday.jpg",
            Message(
                id = MessageId(PeerId(1), 1),
                senderId = null,
                text = null,
                date = 0,
                outgoing = false,
                mediaKind = null,
                fileName = "holiday.jpg",
            ).chatListPreviewSource(),
        )
    }

    @Test
    fun displayPreviewKeepsComposedGroupSender() {
        // Rust stores "<sender>: Photo" for groups; the label must not be prepended again.
        val chat = Chat(
            id = PeerId(1),
            title = "пингвинчики под AGPL-3.0",
            isGroup = true,
            lastMessagePreview = "вова: Photo",
            lastMessageMediaKind = "photo",
        )
        assertEquals("вова: Photo", chat.displayPreview())
    }

    @Test
    fun displayPreviewKeepsComposedGroupCaption() {
        val chat = Chat(
            id = PeerId(1),
            title = "Group",
            isGroup = true,
            lastMessagePreview = "вова: Sticker, смотри",
            lastMessageMediaKind = "sticker",
        )
        assertEquals("вова: Sticker, смотри", chat.displayPreview())
    }

    @Test
    fun displayPreviewStillLabelsRawGroupCaption() {
        val chat = Chat(
            id = PeerId(1),
            title = "Group",
            isGroup = true,
            lastMessagePreview = "привет",
            lastMessageSenderName = "вова",
            lastMessageMediaKind = "photo",
        )
        assertEquals("вова: Photo, привет", chat.displayPreview())
    }

    @Test
    fun displayPreviewKeepsRawGroupCaptionThatStartsWithTheLabel() {
        val chat = Chat(
            id = PeerId(1),
            title = "Group",
            isGroup = true,
            lastMessagePreview = "Photo",
            lastMessageSenderName = "вова",
            lastMessageMediaKind = "photo",
        )
        assertEquals("вова: Photo", chat.displayPreview())
    }

    @Test
    fun displayPreviewDropsStoredStickerFileName() {
        val chat = Chat(
            id = PeerId(1),
            title = "Ada",
            lastMessagePreview = "Sticker, sticker.webp",
            lastMessageMediaKind = "sticker",
        )
        assertEquals("Sticker", chat.displayPreview())
    }

    @Test
    fun displayPreviewDropsStoredGroupFileName() {
        val chat = Chat(
            id = PeerId(1),
            title = "Group",
            isGroup = true,
            lastMessagePreview = "вова: GIF, mp4.mp4",
            lastMessageMediaKind = "gif",
        )
        assertEquals("вова: GIF", chat.displayPreview())
    }

    @Test
    fun displayPreviewKeepsTextCaptionsAndDocumentNames() {
        assertEquals(
            "Photo, привет",
            Chat(
                id = PeerId(1),
                title = "Ada",
                lastMessagePreview = "Photo, привет",
                lastMessageMediaKind = "photo",
            ).displayPreview(),
        )
        assertEquals(
            "Document, report.pdf",
            Chat(
                id = PeerId(1),
                title = "Ada",
                lastMessagePreview = "Document, report.pdf",
                lastMessageMediaKind = "document",
            ).displayPreview(),
        )
    }

    @Test
    fun migrationServiceRowIsAPlaceholder() {
        assertEquals(
            true,
            Chat(
                id = PeerId(7),
                title = "forum",
                isGroup = true,
                lastMessagePreview = "migrate_to\u001F\u001F",
                lastMessageMediaKind = "service",
            ).isMigratedServicePlaceholder(),
        )
    }
}
