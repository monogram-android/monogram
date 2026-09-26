package org.monogram.core.common.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.push.CHANNEL_ID_OFFSET

class TelegramLinksTest {
    @Test
    fun parsesUsernameHttpsAndAt() {
        val https = parseTelegramLink("https://t.me/durov") as TelegramLink.Username
        assertEquals("durov", https.username)
        val mention = parseTelegramLink("@durov") as TelegramLink.Username
        assertEquals("durov", mention.username)
        val telegramMe = parseTelegramLink("https://telegram.me/durov/12") as TelegramLink.Username
        assertEquals(12, telegramMe.messageId)
    }

    @Test
    fun parsesPrivateChannelAndInvite() {
        val channel = parseTelegramLink("https://t.me/c/123456/99") as TelegramLink.PrivateChannel
        assertEquals(123456L, channel.channelId)
        assertEquals(-(CHANNEL_ID_OFFSET + 123456L), channel.chatId)
        val invite = parseTelegramLink("https://t.me/+AbCdEf") as TelegramLink.Invite
        assertEquals("AbCdEf", invite.hash)
        val joinchat = parseTelegramLink("https://t.me/joinchat/AbCdEf") as TelegramLink.Invite
        assertEquals("AbCdEf", joinchat.hash)
    }

    @Test
    fun parsesTgSchemeAndCustomMePrefix() {
        val tg = parseTelegramLink("tg://resolve?domain=durov&post=7") as TelegramLink.Username
        assertEquals("durov", tg.username)
        assertEquals(7, tg.messageId)
        val custom = parseTelegramLink("https://example.me/durov", extraPrefix = "https://example.me/")
            as TelegramLink.Username
        assertEquals("durov", custom.username)
    }

    @Test
    fun rejectsReservedAndForeignHosts() {
        assertNull(parseTelegramLink("https://t.me/addstickers/foo"))
        assertNull(parseTelegramLink("https://example.com/durov"))
        assertNull(parseTelegramLink("https://t.me/ab"))
    }

    @Test
    fun shareAndStartParam() {
        val share = parseTelegramLink("https://t.me/share/url?url=hello") as TelegramLink.Share
        assertEquals("hello", share.text)
        val bot = parseTelegramLink("https://t.me/mybot?start=payload") as TelegramLink.Username
        assertEquals("payload", bot.start)
    }
}
