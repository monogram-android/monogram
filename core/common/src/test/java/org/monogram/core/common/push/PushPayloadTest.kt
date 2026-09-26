package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.NotifySettings
import org.monogram.core.models.PeerId
import org.monogram.core.models.PushTokenType

class PushPayloadTest {
    @Test
    fun tokenTypesMatchTelegramDocs() {
        assertEquals(2, PushTokenType.Fcm.code)
        assertEquals(4, PushTokenType.Simple.code)
        assertEquals(10, PushTokenType.WebPush.code)
    }

    @Test
    fun webPushJsonUsesType10AndFallsBackToSimplePush() {
        val (webType, webToken) = webPushRegistration(
            "https://push.example/ep",
            "p256",
            "auth",
        )
        assertEquals(PushTokenType.WebPush, webType)
        assertEquals(10, webType.code)
        assertTrue(webToken.contains("\"endpoint\":\"https://push.example/ep\""))
        assertTrue(webToken.contains("\"p256dh\":\"p256\""))
        assertTrue(webToken.contains("\"auth\":\"auth\""))
        val (simpleType, simpleToken) = webPushRegistration("https://push.example/ep", null, null)
        assertEquals(PushTokenType.Simple, simpleType)
        assertEquals(4, simpleType.code)
        assertEquals("https://push.example/ep", simpleToken)
    }

    @Test
    fun parsesWrappedDataAndResolvesChannelId() {
        val json = """{"data":{"loc_key":"CHANNEL_MESSAGE_TEXT","loc_args":["News","hello"],"custom":{"channel_id":42,"msg_id":9,"silent":1}}}"""
        val payload = parsePushPayload(json)
        assertEquals("CHANNEL_MESSAGE_TEXT", payload.locKey)
        assertEquals(-(CHANNEL_ID_OFFSET + 42), payload.chatId)
        assertEquals(9, payload.messageId)
        assertTrue(payload.silent)
        assertEquals(PushAction.Show, payload.action)
        assertEquals(PushChannelKind.Channel, payload.channelKind)
        assertEquals("News", payload.title)
        assertTrue(payload.body.contains("hello"))
    }

    /** Telegram sends some numeric custom fields as quoted strings. */
    @Test
    fun quotedNumericCustomFieldsStillResolve() {
        val json = """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"user_id":"7","custom":{"channel_id":"42","msg_id":"9","max_id":"11","silent":"1","mention":"1"}}"""
        val payload = parsePushPayload(json)
        assertEquals(-(CHANNEL_ID_OFFSET + 42), payload.chatId)
        assertEquals(9, payload.messageId)
        assertEquals(11, payload.maxId)
        assertEquals(7L, payload.userId)
        assertTrue(payload.silent)
        assertTrue(payload.mention)
    }

    @Test
    fun serviceKeysDoNotShow() {
        assertEquals(PushAction.Delete, parsePushPayload("""{"loc_key":"MESSAGE_DELETED","custom":{"from_id":7,"messages":"1,2"}}""").action)
        assertEquals(PushAction.ReadHistory, actionFor("READ_HISTORY"))
        assertEquals(PushAction.SessionRevoke, actionFor("SESSION_REVOKE"))
        assertEquals(PushAction.Wake, actionFor("MESSAGE_MUTED"))
    }

    @Test
    fun mutePolicyHidesMutedPeerUnlessMention() {
        val payload = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7}}""")
        val muted = NotificationPolicyState(
            users = NotifySettings(muteUntil = Int.MAX_VALUE),
            exceptions = mapOf(7L to NotifySettings(muteUntil = Int.MAX_VALUE)),
        )
        val hidden = decideNotification(payload, muted, nowSeconds = 10, appInForeground = false)
        assertFalse(hidden.show)
        val mention = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7,"mention":1}}""")
        val shown = decideNotification(mention, muted, nowSeconds = 10, appInForeground = false)
        assertTrue(shown.show)
    }

    @Test
    fun folderMuteAndOpenChatSuppressBanner() {
        val payload = parsePushPayload("""{"loc_key":"CHAT_MESSAGE_TEXT","loc_args":["Ada","Group","hi"],"custom":{"chat_id":5}}""")
        val foldered = NotificationPolicyState(folderMutedChatIds = setOf(-5L))
        assertFalse(decideNotification(payload, foldered, 10, false).show)
        val open = decideNotification(payload, NotificationPolicyState(), 10, true, openChatId = -5L)
        assertFalse(open.show)
    }

    @Test
    fun unescapesJsonForwardSlashesInMessageText() {
        val json = """{"loc_key":"MESSAGE_TEXT","loc_args":["Mama","https:\/\/market.yandex.ru\/cc\/B2ZzsM"],"custom":{"from_id":7,"msg_id":1}}"""
        val payload = parsePushPayload(json)
        assertEquals("Mama", payload.title)
        assertEquals("https://market.yandex.ru/cc/B2ZzsM", payload.body)
        assertEquals("https://market.yandex.ru/cc/B2ZzsM", notificationHttpUrl(payload.body))
    }

    @Test
    fun payloadFieldsUnescapeQuotesNewlinesAndUnicode() {
        val json = """{"loc_key":"MESSAGE_TEXT","loc_args":["a\/b","say \"hi\"","line\nbreak","\u003c","back\\slash"]}"""
        val args = parsePushPayload(json).locArgs
        assertEquals("a/b", args[0])
        assertEquals("say \"hi\"", args[1])
        assertEquals("line\nbreak", args[2])
        assertEquals("<", args[3])
        assertEquals("back\\slash", args[4])
        assertEquals(null, notificationHttpUrl("hello https://example.com"))
        assertEquals(null, notificationHttpUrl("not a url"))
    }

    @Test
    fun formatUnknownLocKeyJoinsArgs() {
        val formatted = formatLocKey("CUSTOM_KEY", listOf("Ada", "hi"))
        assertEquals("Ada", formatted.first)
        assertTrue(formatted.second.contains("hi"))
    }

    @Test
    fun redactTokenHidesMiddle() {
        assertEquals("abcd…wxyz", redactToken("abcdefghijklmnopqrstuvwxyz"))
        assertEquals("", redactToken(""))
    }

    @Test
    fun folderMemberIdsDropExclusions() {
        val ids = folderMemberIds(listOf(PeerId(1), PeerId(2)), listOf(PeerId(2)))
        assertEquals(setOf(1L), ids)
    }
}
