package org.monogram.feature.chats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.Chat
import org.monogram.core.models.PeerId

class ChatListPreviewTextTest {
    private fun chat(
        preview: String?,
        mediaKind: String? = null,
        outgoing: Boolean = false,
        isGroup: Boolean = false,
        isChannel: Boolean = false,
        senderName: String? = null,
    ) = Chat(
        id = PeerId(1),
        title = "Chat",
        isGroup = isGroup,
        isChannel = isChannel,
        lastMessagePreview = preview,
        lastMessageMediaKind = mediaKind,
        lastMessageOutgoing = outgoing,
        lastMessageSenderName = senderName,
    )

    private fun preview(chat: Chat) = chatListPreviewText(
        chat = chat,
        youLabel = "You",
        someoneLabel = "Someone",
        mediaLabel = { kind -> if (kind == "webpage") "Link" else null },
        serviceTemplate = null,
    )

    @Test
    fun linkMessageShowsLabelNotTheUrl() {
        val text = preview(
            chat(
                preview = "https://x.com/pyramonkey/status/12345",
                mediaKind = "webpage",
                outgoing = true,
                isGroup = true,
                senderName = "You",
            ),
        )
        assertEquals("You: Link", text)
    }

    @Test
    fun channelLinkMessageHasNoSenderPrefix() {
        val text = preview(
            chat(
                preview = "https://t.me/monogram",
                mediaKind = "webpage",
                isChannel = true,
            ),
        )
        assertEquals("Link", text)
    }

    @Test
    fun linkWithRealCaptionKeepsTheCaption() {
        val text = preview(
            chat(
                preview = "look at this https://x.com/monogram",
                mediaKind = "webpage",
            ),
        )
        assertEquals("Link, look at this https://x.com/monogram", text)
    }

    @Test
    fun plainMessagesKeepTheirText() {
        assertEquals("привет", preview(chat(preview = "привет")))
        assertEquals("You: hi", preview(chat(preview = "hi", outgoing = true, isGroup = true)))
    }

    @Test
    fun nakedUrlDetection() {
        assertTrue("https://x.com/a".isNakedUrl())
        assertTrue("HTTP://example.com".isNakedUrl())
        assertTrue("x.com/pyramonkey/status/1".isNakedUrl())
        assertTrue("www.example.com".isNakedUrl())
        assertTrue("t.me/monogram".isNakedUrl())
        assertFalse("3.14".isNakedUrl())
        assertFalse("hello world".isNakedUrl())
        assertFalse("https://x.com/a b".isNakedUrl())
        assertFalse("@durov".isNakedUrl())
        assertFalse("".isNakedUrl())
    }

    @Test
    fun senderPrefixSplitsOnlyForOutgoingYou() {
        assertEquals("You:" to "hi there", splitPreviewSender("You: hi there", "You"))
        assertEquals("You:" to "hi", splitPreviewSender("you: hi", "You"))
        assertEquals(null to "You:", splitPreviewSender("You:", "You"))
        assertEquals(null to "Вы: hi", splitPreviewSender("Вы: hi", "You"))
        assertEquals(null to "you are welcome", splitPreviewSender("you are welcome", "You"))
    }

    @Test
    fun mediaKindsMapToIcons() {
        assertEquals(ChatPreviewMedia.Photo, chatPreviewMedia("photo"))
        assertEquals(ChatPreviewMedia.Voice, chatPreviewMedia("voice"))
        assertEquals(ChatPreviewMedia.Video, chatPreviewMedia("video_note"))
        assertEquals(ChatPreviewMedia.Link, chatPreviewMedia("webpage"))
        assertEquals(ChatPreviewMedia.Checklist, chatPreviewMedia("todo"))
        assertEquals(ChatPreviewMedia.Sticker, chatPreviewMedia("sticker_video"))
        assertNull(chatPreviewMedia("service"))
        assertNull(chatPreviewMedia(null))
    }

    private fun archiveChat(id: Long, title: String) = Chat(id = PeerId(id), title = title)

    @Test
    fun archivePreviewListsTheLatestChatTitles() {
        val chats = listOf(
            archiveChat(1, "PixelOS Chat"),
            archiveChat(2, "VR Games"),
            archiveChat(3, "Dev"),
            archiveChat(4, "Older"),
        )
        assertEquals("PixelOS Chat, VR Games, Dev", archivePreviewTitles(chats, "Chat"))
    }

    @Test
    fun archivePreviewFallsBackForBlankTitles() {
        val chats = listOf(archiveChat(1, "   "), archiveChat(2, "VR Games"))
        assertEquals("Chat, VR Games", archivePreviewTitles(chats, "Chat"))
    }

    @Test
    fun archivePreviewIsEmptyWhenThereIsNothingToPreview() {
        assertNull(archivePreviewTitles(emptyList(), "Chat"))
        assertNull(archivePreviewTitles(listOf(archiveChat(1, "A")), "Chat", limit = 0))
    }
}
