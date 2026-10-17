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
        val (blankType, blankToken) = webPushRegistration("https://push.example/ep", "", "auth")
        assertEquals(PushTokenType.Simple, blankType)
        assertEquals("https://push.example/ep", blankToken)
        val (_, escaped) = webPushRegistration("https://push.example/a\"b", "p\\256", "a uth")
        assertTrue(escaped.contains("\"endpoint\":\"https://push.example/a\\\"b\""))
        assertTrue(escaped.contains("\"p256dh\":\"p\\\\256\""))
    }

    @Test
    fun autoOnGmsUsesFcmButForceUnifiedPushDoesNot() {
        val auto = planPushProvider(
            requested = PushProviderMode.Auto,
            playServices = true,
            firebaseConfigured = true,
            unifiedPushAvailable = true,
        )
        assertEquals(PushProviderMode.Auto, auto.mode)
        assertEquals(PushTransport.Fcm, auto.register)

        val noFirebase = planPushProvider(
            requested = PushProviderMode.Auto,
            playServices = true,
            firebaseConfigured = false,
            unifiedPushAvailable = true,
        )
        assertEquals(PushTransport.UnifiedPush, noFirebase.register)

        val forced = planPushProvider(
            requested = PushProviderMode.ForceUnifiedPush,
            playServices = true,
            firebaseConfigured = true,
            unifiedPushAvailable = false,
        )
        assertEquals(PushProviderMode.ForceUnifiedPush, forced.mode)
        assertEquals(PushTransport.UnifiedPush, forced.register)

        val noPlay = planPushProvider(
            requested = PushProviderMode.ForceFcm,
            playServices = false,
            firebaseConfigured = false,
            unifiedPushAvailable = true,
        )
        assertEquals(PushProviderMode.Auto, noPlay.mode)
        assertEquals(PushTransport.UnifiedPush, noPlay.register)
        assertEquals(PLAY_SERVICES_UNAVAILABLE, noPlay.status)
        assertFalse(acceptsUnifiedPushEndpoint(PushProviderMode.ForceFcm, PushTransport.Fcm))
        assertFalse(acceptsUnifiedPushEndpoint(PushProviderMode.Off, null))
        assertTrue(acceptsUnifiedPushEndpoint(PushProviderMode.ForceUnifiedPush, PushTransport.UnifiedPush))
        assertFalse(acceptsFcmToken(PushTransport.UnifiedPush))
        assertFalse(acceptsFcmToken(null))
        assertTrue(acceptsFcmToken(PushTransport.Fcm))

        val nowhere = planPushProvider(
            requested = PushProviderMode.Auto,
            playServices = false,
            firebaseConfigured = false,
            unifiedPushAvailable = false,
        )
        assertEquals(null, nowhere.register)
        assertEquals(NO_PUSH_STATUS, nowhere.status)

        val off = planPushProvider(
            requested = PushProviderMode.Off,
            playServices = true,
            firebaseConfigured = true,
            unifiedPushAvailable = true,
        )
        assertEquals(PushProviderMode.Off, off.mode)
        assertEquals(null, off.register)
        assertEquals(PushProviderMode.Auto, PushProviderMode.fromStored(null))
        assertEquals(PushProviderMode.Auto, PushProviderMode.fromStored("nope"))
    }

    @Test
    fun simplePushUsesGatewayOnlyWithoutKeys() {
        val (webType, webToken) = webPushRegistration("https://up.example/a", "p256", "auth")
        assertEquals(PushTokenType.WebPush, webType)
        assertFalse(webToken.contains("gateway.example"))
        val raw = simplePushEndpoint("https://up.example/a", "")
        assertEquals("https://up.example/a", raw.token)
        assertEquals(SIMPLE_PUSH_PUT_WARNING, raw.warning)
        val proxied = simplePushEndpoint("https://up.example/a", "https://gateway.example/base")
        assertEquals(
            "https://gateway.example/base/?endpoint=https%3A%2F%2Fup.example%2Fa",
            proxied.token,
        )
        assertEquals(null, proxied.warning)
        assertEquals("up.example", endpointHost(webToken, PushTokenType.WebPush))
        assertFalse(endpointHost(webToken, PushTokenType.WebPush).contains("p256"))
        assertEquals("up.example", endpointHost("https://up.example/secret", PushTokenType.Simple))
        assertTrue(
            showsUnifiedPushSettings(PushProviderMode.ForceUnifiedPush, playServices = true, transport = "fcm"),
        )
        assertFalse(showsUnifiedPushSettings(PushProviderMode.ForceFcm, playServices = true, transport = "fcm"))
        assertFalse(showsUnifiedPushSettings(PushProviderMode.Auto, playServices = true, transport = "fcm"))
        assertTrue(showsUnifiedPushSettings(PushProviderMode.Auto, playServices = false, transport = "none"))
    }

    @Test
    fun providerSwitchUnregistersPreviousTokenOnlyWhenItChanges() {
        assertEquals(
            PushRegistrationChange(),
            pushRegistrationChange(null, "", PushTokenType.Fcm, "fcm-1"),
        )
        assertEquals(
            PushRegistrationChange(),
            pushRegistrationChange(PushTokenType.Fcm, "fcm-1", PushTokenType.Fcm, "fcm-1"),
        )
        assertEquals(
            PushRegistrationChange(previous = PushTokenType.Fcm to "fcm-1"),
            pushRegistrationChange(PushTokenType.Fcm, "fcm-1", PushTokenType.WebPush, "{\"endpoint\"}"),
        )
        assertEquals(
            PushRegistrationChange(
                previous = PushTokenType.WebPush to "{\"endpoint\"}",
                unregisterUnifiedPush = true,
            ),
            pushRegistrationChange(PushTokenType.WebPush, "{\"endpoint\"}", PushTokenType.Fcm, "fcm-1"),
        )
        assertEquals(
            PushRegistrationChange(previous = PushTokenType.WebPush to "old"),
            pushRegistrationChange(PushTokenType.WebPush, "old", PushTokenType.WebPush, "new"),
        )
        assertEquals(
            PushRegistrationChange(previous = PushTokenType.Simple to "https://push.example/ep"),
            pushRegistrationChange(PushTokenType.Simple, "https://push.example/ep", PushTokenType.WebPush, "{\"endpoint\"}"),
        )
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
    fun incomingBodiesCoverBase64QuotedJsonBinaryAndPOnly() {
        val json = """{"loc_key":"MESSAGE_TEXT","loc_args":["Ada","hi"],"custom":{"from_id":7}}"""
        val plain = normalizeIncomingBody(fcm = false, json.encodeToByteArray())
        assertEquals("MESSAGE_TEXT", presentKey(plain))

        val quoted = "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        assertEquals("MESSAGE_TEXT", presentKey(normalizeIncomingBody(fcm = false, quoted.encodeToByteArray())))

        val brittle = "\"{\"loc_key\":\"READ_HISTORY\",\"custom\":{\"max_id\":4}}\""
        assertEquals(PushAction.ReadHistory, parsePushPayload(presentJson(normalizeIncomingBody(fcm = false, brittle.encodeToByteArray()))).action)

        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        assertEquals("MESSAGE_TEXT", presentKey(normalizeIncomingBody(fcm = false, encoded.encodeToByteArray())))

        val pOnly = "{\"p\":\"{\\\"loc_key\\\":\\\"MESSAGE_DELETED\\\",\\\"custom\\\":{\\\"from_id\\\":7}}\"}"
        assertEquals(
            PushAction.Delete,
            parsePushPayload(presentJson(normalizeIncomingBody(fcm = false, pOnly.encodeToByteArray()))).action,
        )
        assertTrue(normalizeIncomingBody(fcm = false, "{\"p\":\"not-a-payload\"}".encodeToByteArray()) is IncomingPush.Wake)
        assertTrue(normalizeIncomingBody(fcm = false, "noise loc_key noise".encodeToByteArray()) is IncomingPush.Wake)
        assertTrue(normalizeIncomingBody(fcm = false, byteArrayOf(0x00, 0xff.toByte(), 0xfe.toByte())) is IncomingPush.Wake)
        assertTrue(normalizeIncomingBody(fcm = false, ByteArray(0)) is IncomingPush.Wake)
        assertTrue(normalizeIncomingBody(fcm = true, null) is IncomingPush.Wake)
        val cipher = normalizeIncomingBody(fcm = true, "cipher".encodeToByteArray())
        assertTrue(cipher is IncomingPush.Decrypt)
        assertEquals("cipher", (cipher as IncomingPush.Decrypt).cipher)
        assertTrue(normalizeDecrypted("not json") is IncomingPush.Wake)
        assertEquals("MESSAGE_TEXT", presentKey(normalizeDecrypted(json)))
        assertEquals(PushAction.SessionRevoke, actionFor("SESSION_REVOKE"))
        assertEquals(PushAction.ReadReaction, actionFor("READ_REACTION"))
        assertEquals(PushAction.Show, actionFor("MESSAGE_TEXT"))
    }

    private fun presentKey(incoming: IncomingPush): String = parsePushPayload(presentJson(incoming)).locKey

    private fun presentJson(incoming: IncomingPush): String {
        assertTrue(incoming is IncomingPush.Present)
        return (incoming as IncomingPush.Present).json
    }

    @Test
    fun giftsAndCallTogglesChangeTheDecision() {
        val gift = parsePushPayload("""{"loc_key":"MESSAGE_GIFT","loc_args":["Ada"],"custom":{"from_id":7}}""")
        val hidden = decideNotification(gift, NotificationPolicyState(giftsEnabled = false), 10, false)
        assertFalse(hidden.show)
        val call = parsePushPayload("""{"loc_key":"PHONE_CALL_MISSED","loc_args":["Ada"],"custom":{"from_id":7}}""")
        val quiet = decideNotification(
            call,
            NotificationPolicyState(callsRingtone = "none", callsVibrate = "off"),
            10,
            false,
        )
        assertTrue(quiet.show)
        assertFalse(quiet.sound)
        assertFalse(quiet.vibrate)
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
