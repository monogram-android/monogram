package org.monogram.feature.dialog.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId

class MessageReplyQuoteTest {
    @Test
    fun serverQuoteWinsOverTheQuotedMessage() {
        val quoted = message(text = "original text", fileName = "song.mp3")

        assertEquals("server quote", replyQuoteText("server quote", quoted))
    }

    @Test
    fun captionlessAudioFallsBackToTheFileName() {
        val quoted = message(mediaKind = "audio", fileName = "Title — Performer")

        assertEquals("Title — Performer", replyQuoteText(null, quoted))
    }

    @Test
    fun captionlessDocumentFallsBackToTheFileName() {
        val quoted = message(mediaKind = "document", fileName = "report.pdf")

        assertEquals("report.pdf", replyQuoteText(null, quoted))
    }

    @Test
    fun blankServerQuoteFallsThroughToTheQuotedMessage() {
        val quoted = message(text = "original text")

        assertEquals("original text", replyQuoteText("   ", quoted))
    }

    @Test
    fun stickerFileNameIsNotUsedAsPreviewText() {
        val quoted = message(mediaKind = "sticker", fileName = "sticker.webp")

        assertNull(replyQuoteText(null, quoted))
    }

    @Test
    fun missingQuotedMessageHasNoPreviewText() {
        assertNull(replyQuoteText(null, null))
    }

    private fun message(
        text: String? = null,
        fileName: String? = null,
        mediaKind: String? = null,
    ) = Message(
        id = MessageId(PeerId(1), 7),
        senderId = null,
        text = text,
        date = 0,
        outgoing = false,
        mediaKind = mediaKind,
        fileName = fileName,
    )
}