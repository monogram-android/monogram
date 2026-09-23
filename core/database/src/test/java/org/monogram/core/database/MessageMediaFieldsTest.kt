package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.database.entity.MessageEntity
import org.monogram.core.models.Message
import org.monogram.core.models.MessageId
import org.monogram.core.models.PeerId
import org.monogram.core.models.ReplyButton
import org.monogram.core.models.ReplyButtonType
import org.monogram.core.models.ReplyMarkup
import org.monogram.core.models.ReplyMarkupKind

class MessageMediaFieldsTest {
    @Test
    fun schemaVersionIsCurrent() {
        assertEquals(2, DatabaseProvider.SCHEMA_VERSION)
    }

    @Test
    fun onlyCurrentSchemaIsCompatible() {
        assertTrue(DatabaseProvider.hasMigrationPath(1))
        assertTrue(DatabaseProvider.hasMigrationPath(0, 1))
        assertTrue(DatabaseProvider.hasMigrationPath(1, 2))
        assertFalse(DatabaseProvider.hasMigrationPath(32))
        assertFalse(DatabaseProvider.hasMigrationPath(3, 2))
    }

    @Test
    fun messageRoundtripsDurationAndSize() {
        val message = Message(
            id = MessageId(PeerId(42), 7),
            senderId = PeerId(1),
            text = null,
            date = 1L,
            editDate = 2L,
            outgoing = false,
            pending = true,
            randomId = 55L,
            mediaCacheKey = "doc:1",
            mediaKind = "video",
            thumbCacheKey = "doc:1:thumb",
            mediaDuration = 12,
            mediaWidth = 1280,
            mediaHeight = 720,
            fileSize = 8_000_000,
            supportsStreaming = true,
            replyToMsgId = 3,
            fwdFrom = "Alice",
            fwdFromId = 100L,
            fwdDate = 1_700_000_000L,
            viaBot = "gif",
            senderName = "Bob",
            replyMarkup = ReplyMarkup(
                kind = ReplyMarkupKind.Inline,
                rows = listOf(
                    listOf(ReplyButton(ReplyButtonType.Url, "Open", url = "https://t.me")),
                ),
            ),
        )
        val entity = message.toEntity()
        assertEquals(12, entity.mediaDuration)
        assertEquals(1280, entity.mediaWidth)
        assertEquals(720, entity.mediaHeight)
        val back = entity.toModel()
        assertEquals(12, back.mediaDuration)
        assertEquals(1280, back.mediaWidth)
        assertEquals(720, back.mediaHeight)
        assertEquals("video", back.mediaKind)
        assertEquals("doc:1:thumb", back.thumbCacheKey)
        assertEquals(3, back.replyToMsgId)
        assertEquals("Alice", back.fwdFrom)
        assertEquals(100L, back.fwdFromId)
        assertEquals(1_700_000_000L, back.fwdDate)
        assertEquals("gif", back.viaBot)
        assertEquals("Bob", back.senderName)
        assertEquals(2L, back.editDate)
        assertEquals(ReplyMarkupKind.Inline, back.replyMarkup?.kind)
        assertEquals("https://t.me", back.replyMarkup?.rows?.single()?.single()?.url)
        assertEquals(true, back.pending)
        assertEquals(55L, back.randomId)
        assertEquals(true, back.supportsStreaming)
        assertEquals(8_000_000L, back.fileSize)
    }

    @Test
    fun chatRoundtripsUnreadMentionAndReactionCounts() {
        val chat = org.monogram.core.models.Chat(
            id = org.monogram.core.models.PeerId(9),
            title = "Ada",
            unreadMentionsCount = 3,
            unreadReactionsCount = 2,
        )
        val back = chat.toEntity().toModel()
        assertEquals(3, back.unreadMentionsCount)
        assertEquals(2, back.unreadReactionsCount)
    }

    @Test
    fun entityDefaultsKeepNullMetrics() {
        val entity = MessageEntity(
            chatId = 1L,
            id = 2,
            senderId = null,
            text = "hi",
            date = 0L,
            outgoing = true,
            mediaCacheKey = null,
            mediaKind = null,
        )
        val model = entity.toModel()
        assertEquals(null, model.mediaDuration)
        assertEquals(null, model.mediaWidth)
        assertEquals(null, model.mediaHeight)
        assertEquals(false, model.supportsStreaming)
    }
}
