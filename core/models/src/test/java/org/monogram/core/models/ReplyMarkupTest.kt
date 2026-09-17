package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyMarkupTest {
    @Test
    fun parseInlineUrlAndCallback() {
        val parsed = ReplyMarkups.parse(
            """{"k":"inline","rows":[[{"t":"url","x":"Open","u":"https://t.me"},{"t":"cb","x":"Go","d":"6162"}]]}""",
        )
        requireNotNull(parsed)
        assertEquals(ReplyMarkupKind.Inline, parsed.kind)
        assertEquals(2, parsed.rows.single().size)
        assertEquals(ReplyButtonType.Url, parsed.rows[0][0].type)
        assertEquals("https://t.me", parsed.rows[0][0].url)
        assertEquals(ReplyButtonType.Callback, parsed.rows[0][1].type)
        assertEquals("6162", parsed.rows[0][1].dataHex)
    }

    @Test
    fun parseBotSendableButtonTypes() {
        val parsed = ReplyMarkups.parse(
            """{"k":"inline","rows":[[{"t":"copy","x":"Copy","c":"payload"},{"t":"web","x":"App","u":"https://t.me"},{"t":"login","x":"Login","u":"https://t.me"}]]}""",
        )
        requireNotNull(parsed)
        assertEquals(ReplyButtonType.Copy, parsed.rows[0][0].type)
        assertEquals("payload", parsed.rows[0][0].copyText)
        assertEquals(ReplyButtonType.WebApp, parsed.rows[0][1].type)
        assertEquals(ReplyButtonType.Login, parsed.rows[0][2].type)
        val keyboard = ReplyMarkups.parse(
            """{"k":"keyboard","rows":[[{"t":"contact","x":"Phone"},{"t":"geo","x":"Loc"}]]}""",
        )
        requireNotNull(keyboard)
        assertEquals(ReplyButtonType.RequestContact, keyboard.rows[0][0].type)
        assertEquals(ReplyButtonType.RequestLocation, keyboard.rows[0][1].type)
    }

    @Test
    fun parseKeyboardAndHide() {
        val keyboard = ReplyMarkups.parse(
            """{"k":"keyboard","resize":true,"rows":[[{"t":"text","x":"Yes"}]]}""",
        )
        requireNotNull(keyboard)
        assertEquals(ReplyMarkupKind.Keyboard, keyboard.kind)
        assertTrue(keyboard.resize)
        assertEquals("Yes", keyboard.rows[0][0].text)
        assertEquals(ReplyMarkupKind.Hide, ReplyMarkups.parse("""{"k":"hide"}""")?.kind)
        assertEquals(ReplyMarkupKind.ForceReply, ReplyMarkups.parse("""{"k":"force"}""")?.kind)
        assertNull(ReplyMarkups.parse(null))
    }

    @Test
    fun serializeRoundTripKeepsSwitchInline() {
        val source = ReplyMarkup(
            kind = ReplyMarkupKind.Inline,
            rows = listOf(
                listOf(
                    ReplyButton(
                        type = ReplyButtonType.SwitchInline,
                        text = "Search",
                        query = "cats",
                        samePeer = true,
                    ),
                ),
            ),
        )
        val parsed = ReplyMarkups.parse(ReplyMarkups.serialize(source))
        requireNotNull(parsed)
        val button = parsed.rows.single().single()
        assertEquals(ReplyButtonType.SwitchInline, button.type)
        assertEquals("cats", button.query)
        assertTrue(button.samePeer)
    }

    @Test
    fun parseKeepsEscapedTextAndUnknownButtons() {
        val parsed = ReplyMarkups.parse(
            """{"k":"keyboard","rows":[[{"t":"text","x":"Say \"hi\" }"},{"t":"xyz","x":"Pay"}]]}""",
        )
        requireNotNull(parsed)
        assertEquals("Say \"hi\" }", parsed.rows[0][0].text)
        assertEquals(ReplyButtonType.Other, parsed.rows[0][1].type)
        assertEquals("Pay", parsed.rows[0][1].text)
    }

    @Test
    fun parseMalformedAndMissingFieldsDoNotCrash() {
        assertNull(ReplyMarkups.parse("{not-json"))
        assertNull(ReplyMarkups.parse("{\"k\":\"nope\"}"))
        val missingText = ReplyMarkups.parse(
            """{"k":"inline","rows":[[{"t":"url","u":"https://t.me"}]]}""",
        )
        requireNotNull(missingText)
        assertTrue(missingText.rows.single().isEmpty())
        val nested = ReplyMarkups.parse(
            """{"k":"inline","rows":[[{"t":"cb","x":"Go","d":"6162","extra":{"n":1}}]]}""",
        )
        requireNotNull(nested)
        assertEquals("6162", nested.rows.single().single().dataHex)
    }

    @Test
    fun latestBotKeyboardPrefersNewestKeyboardAndHonorsHide() {
        fun msg(id: Int, markup: ReplyMarkup?) = Message(
            id = MessageId(PeerId(1), id),
            senderId = PeerId(2),
            text = "m$id",
            date = id.toLong(),
            outgoing = false,
            replyMarkup = markup,
        )
        val keyboard = ReplyMarkup(
            kind = ReplyMarkupKind.Keyboard,
            rows = listOf(listOf(ReplyButton(ReplyButtonType.Text, "Yes"))),
        )
        val hidden = listOf(
            msg(1, keyboard),
            msg(2, ReplyMarkup(kind = ReplyMarkupKind.Hide)),
        )
        assertNull(ReplyMarkups.latestBotKeyboard(hidden))
        val shown = hidden + msg(3, keyboard)
        assertEquals("Yes", ReplyMarkups.latestBotKeyboard(shown)?.rows?.single()?.single()?.text)
        assertEquals(3, ReplyMarkups.latestBotKeyboardMessage(shown)?.id?.id)
        val inlineOnly = listOf(
            msg(1, ReplyMarkup(
                kind = ReplyMarkupKind.Inline,
                rows = listOf(listOf(ReplyButton(ReplyButtonType.Url, "Open", url = "https://t.me"))),
            )),
        )
        assertNull(ReplyMarkups.latestBotKeyboard(inlineOnly))
    }
}
