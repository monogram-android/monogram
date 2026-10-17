package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStyleTest {
    @Test
    fun directChatHasNoConversationTitle() {
        val style = conversationStyle(message("MESSAGE_TEXT", """{"from_id":7}"""), quiet = false, lastAlertAtMillis = 0L, nowMillis = 1_000L)
        assertTrue(style.messagingStyle)
        assertEquals("chat:7", style.shortcutId)
        assertEquals("peer:7", style.personKey)
        assertEquals(style.shortcutId, style.locusId)
        assertNull(style.conversationTitle)
        assertTrue(style.categoryMessage)
        assertEquals(NotificationConversation.GROUP_KEY, style.groupKey)
        assertTrue(style.shortcutBeforeNotify)
        assertTrue(style.reply)
        assertTrue(style.markRead)
        assertTrue(style.countsAsNewAlert)
        assertFalse(style.onlyAlertOnce)
    }

    @Test
    fun groupsChannelsAndTopicsSetATitle() {
        val group = conversationStyle(
            message("CHAT_MESSAGE_TEXT", """{"chat_id":5}""", args = """["Ada","Group","hi"]"""),
            quiet = false,
            lastAlertAtMillis = 0L,
            nowMillis = 1_000L,
        )
        assertEquals("Group", group.conversationTitle)
        assertEquals("chat:-5", group.shortcutId)
        assertEquals("Ada", group.messageSenderName)
        assertEquals("peer:-5", group.personKey)
        assertEquals(group.personKey, group.messagePersonKey)

        val channel = conversationStyle(
            message("CHANNEL_MESSAGE_TEXT", """{"channel_id":42}""", args = """["News","hello"]"""),
            quiet = false,
            lastAlertAtMillis = 0L,
            nowMillis = 1_000L,
        )
        assertEquals("News", channel.conversationTitle)
        assertEquals("chat:${-(CHANNEL_ID_OFFSET + 42)}", channel.shortcutId)

        val topic = conversationStyle(
            message(
                "CHAT_MESSAGE_TEXT",
                """{"chat_id":5,"topic_id":9,"topic_title":"Plans"}""",
                args = """["Ada","Group","hi"]""",
            ),
            quiet = false,
            lastAlertAtMillis = 0L,
            nowMillis = 1_000L,
        )
        assertEquals("Group · Plans", topic.conversationTitle)
        assertEquals("Group · Plans", topic.shortcutLabel)
        assertEquals("chat:-5", topic.shortcutId)
    }

    @Test
    fun reactionsStoriesAndAccountAlertsAreNotConversations() {
        listOf(
            "REACT_TEXT" to """{"from_id":7}""",
            "STORY_NOTEXT" to """{"from_id":7}""",
            "CONTACT_JOINED" to """{"from_id":7}""",
            "AUTH_UNKNOWN" to """{"from_id":7}""",
        ).forEach { (key, custom) ->
            val style = conversationStyle(message(key, custom), quiet = false, lastAlertAtMillis = 0L, nowMillis = 1_000L)
            assertNull(key, style.shortcutId)
            assertNull(key, style.conversationTitle)
            assertFalse(key, style.reply)
            assertFalse(style.shortcutBeforeNotify)
        }
    }

    @Test
    fun quietRepaintDoesNotCountAsANewAlert() {
        val quiet = conversationStyle(message("MESSAGE_TEXT", """{"from_id":7}"""), quiet = true, lastAlertAtMillis = 0L, nowMillis = 5_000L)
        assertTrue(quiet.onlyAlertOnce)
        assertFalse(quiet.countsAsNewAlert)
        val throttled = conversationStyle(
            message("MESSAGE_TEXT", """{"from_id":7}"""),
            quiet = false,
            lastAlertAtMillis = 4_800L,
            nowMillis = 5_000L,
        )
        assertTrue(throttled.onlyAlertOnce)
        assertFalse(throttled.countsAsNewAlert)
    }

    @Test
    fun shareShortcutsStayRankedAndCappedAfterDismiss() {
        val capped = rankedShareChatIds(listOf(2L, 3L, 4L), chatId = 9L, maxCount = 3)
        assertEquals(listOf(9L, 2L, 3L), capped)
        assertEquals(listOf(4L), evictedShareChatIds(listOf(2L, 3L, 4L), capped))
        assertEquals(listOf(2L, 9L, 3L), rankedShareChatIds(capped, chatId = 2L, maxCount = 3))
        assertEquals(emptyList<Long>(), rankedShareChatIds(listOf(1L), chatId = 2L, maxCount = 0))
    }

    @Test
    fun bubblesStayOffUntilEnabledAndAreSkippedWhenDemoted() {
        assertFalse(shouldAttachBubble(enabled = false, conversation = true, demoted = false))
        assertFalse(shouldAttachBubble(enabled = true, conversation = false, demoted = false))
        assertFalse(shouldAttachBubble(enabled = true, conversation = true, demoted = true))
        assertTrue(shouldAttachBubble(enabled = true, conversation = true, demoted = false))
    }

    @Test
    fun missedCallsDoNotFakeAnAnswerButton() {
        val missed = callNotice("PHONE_CALL_MISSED", canAnswer = false, fullScreenAllowed = true)
        assertTrue(missed.call)
        assertTrue(missed.staysUntilOpened)
        assertFalse(missed.answer)
        assertFalse(missed.fullScreen)
        assertFalse(missed.categoryCall)
        val incoming = callNotice("PHONE_CALL_REQUEST", canAnswer = false, fullScreenAllowed = true)
        assertFalse(incoming.answer)
        assertFalse(incoming.categoryCall)
        val group = callNotice("CHAT_VOICECHAT_START", canAnswer = true, fullScreenAllowed = true)
        assertTrue(group.call)
        assertFalse(group.answer)
        val live = callNotice("STORY_LIVE", canAnswer = true, fullScreenAllowed = true)
        assertFalse(live.answer)
        val ready = callNotice("PHONE_CALL_REQUEST", canAnswer = true, fullScreenAllowed = true)
        assertTrue(ready.answer)
        assertTrue(ready.categoryCall)
        assertTrue(ready.fullScreen)
        assertFalse(callNotice("PHONE_CALL_REQUEST", canAnswer = true, fullScreenAllowed = false).fullScreen)
        assertEquals(PushChannelKind.Calls, channelKindFor("PHONE_CALL_MISSED"))
        assertEquals(PushChannelKind.Calls, channelKindFor("CHAT_VOICECHAT_INVITE"))
    }

    @Test
    fun hiddenPreviewAndDeniedPermissionDoNotLeakOrPost() {
        assertEquals("New message", shadeText(preview = false, body = "secret text", hiddenText = "New message"))
        assertFalse(shadeText(preview = false, body = "secret text", hiddenText = "New message").contains("secret"))
        assertEquals("secret text", shadeText(preview = true, body = "secret text", hiddenText = "New message"))
        assertFalse(mayPostNotifications(sdkInt = 33, permissionGranted = false))
        assertTrue(mayPostNotifications(sdkInt = 33, permissionGranted = true))
        assertTrue(mayPostNotifications(sdkInt = 32, permissionGranted = false))
    }

    private fun message(locKey: String, custom: String, args: String = """["Ada","hi"]"""): PushPayload =
        parsePushPayload("""{"loc_key":"$locKey","loc_args":$args,"custom":$custom}""")
}
