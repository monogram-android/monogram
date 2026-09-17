package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Popup (heads-up) precedence for `decideNotification`. The decision feeds
 * `NotificationChannels.channelFor`, where `popup = true` maps to `IMPORTANCE_HIGH` (peeks) and
 * `popup = false` to a channel that does not peek, while the group summary stays silent.
 */
class NotificationPopupPolicyTest {
    private fun payload(locKey: String = "MESSAGE_TEXT", custom: String = "\"from_id\":7,\"msg_id\":1") =
        parsePushPayload("""{"loc_key":"$locKey","loc_args":["Ada","hi"],"custom":{$custom}}""")

    @Test
    fun categoryPopupOffStopsHeadsUp() {
        val off = NotificationPolicyState(popupUsers = false, popupChats = false, popupBroadcasts = false)
        assertFalse(decideNotification(payload(), off, 10, appInForeground = false).popup)
        assertFalse(decideNotification(payload("CHAT_MESSAGE_TEXT"), off, 10, false).popup)
        assertFalse(decideNotification(payload("CHANNEL_MESSAGE_TEXT"), off, 10, false).popup)
        // The notification still shows in the shade.
        assertTrue(decideNotification(payload(), off, 10, false).show)
    }

    @Test
    fun popupDefaultsToPeekingPerCategory() {
        val state = NotificationPolicyState()
        assertTrue(decideNotification(payload(), state, 10, false).popup)
        assertTrue(decideNotification(payload("CHAT_MESSAGE_TEXT"), state, 10, false).popup)
        assertTrue(decideNotification(payload("CHANNEL_MESSAGE_TEXT"), state, 10, false).popup)
        assertTrue(decideNotification(payload("STORY_NOTEXT"), state, 10, false).popup)
        assertTrue(decideNotification(payload("REACT_TEXT"), state, 10, false).popup)
    }

    @Test
    fun storyAndReactionCategoriesHaveTheirOwnChannelsAndPopupFlags() {
        assertEquals(PushChannelKind.Stories, payload("STORY_NOTEXT").channelKind)
        assertEquals(PushChannelKind.Reactions, payload("REACT_TEXT").channelKind)
        val state = NotificationPolicyState(popupStories = false, popupReactions = false)
        assertFalse(decideNotification(payload("STORY_NOTEXT"), state, 10, false).popup)
        assertFalse(decideNotification(payload("REACT_TEXT"), state, 10, false).popup)
        // Their own flags do not leak into the other categories.
        assertTrue(decideNotification(payload(), state, 10, false).popup)
    }

    @Test
    fun inAppPriorityDisabledStopsHeadsUpOnlyInForeground() {
        val state = NotificationPolicyState(inAppPriority = false)
        assertFalse(decideNotification(payload(), state, 10, appInForeground = true).popup)
        assertTrue(decideNotification(payload(), state, 10, appInForeground = false).popup)
    }

    @Test
    fun chatModeOverridesCategoryPopupBothWays() {
        val quietChat = NotificationPolicyState(
            popupUsers = true,
            peerModes = mapOf(7L to PeerNotificationMode(popup = false)),
        )
        assertFalse(decideNotification(payload(), quietChat, 10, false).popup)
        val loudChat = NotificationPolicyState(
            popupUsers = false,
            inAppPriority = false,
            peerModes = mapOf(7L to PeerNotificationMode(popup = true)),
        )
        assertTrue(decideNotification(payload(), loudChat, 10, false).popup)
        // An explicit chat mode also wins over the in-app priority switch.
        assertTrue(decideNotification(payload(), loudChat, 10, appInForeground = true).popup)
    }

    @Test
    fun silentlyPostedMessagesNeverPeek() {
        val state = NotificationPolicyState()
        val silent = payload(custom = "\"from_id\":7,\"msg_id\":1,\"silent\":1")
        assertFalse(decideNotification(silent, state, 10, false).popup)
        assertFalse(decideNotification(silent, state, 10, false).sound)
        // Even a chat mode asking for a popup cannot revive a silent message.
        val loud = state.copy(peerModes = mapOf(7L to PeerNotificationMode(popup = true, sound = true)))
        assertFalse(decideNotification(silent, loud, 10, false).popup)
    }

    @Test
    fun mutedCategoryWithoutMentionIsHiddenEntirely() {
        val state = NotificationPolicyState(popupChats = false)
        val mentioned = payload("CHAT_MESSAGE_TEXT", "\"chat_id\":5,\"msg_id\":1,\"mention\":1")
        val decision = decideNotification(mentioned, state, 10, false)
        assertTrue(decision.show)
        assertFalse(decision.popup)
    }

    @Test
    fun mutedChatsContributeToTheBadgeOnlyWhenIncluded() {
        val mentioned = parsePushPayload(
            """{"loc_key":"CHAT_MESSAGE_TEXT","loc_args":["Ada","Group","hi"],"custom":{"chat_id":5,"msg_id":1,"mention":1}}""",
        )
        val excluded = NotificationPolicyState(
            badgeMuted = false,
            peerModes = mapOf(-5L to PeerNotificationMode(muteUntil = Int.MAX_VALUE)),
        )
        val decision = decideNotification(mentioned, excluded, 10, false)
        assertTrue(decision.show)
        assertFalse(decision.badge)
        val included = excluded.copy(badgeMuted = true)
        assertTrue(decideNotification(mentioned, included, 10, false).badge)
    }

    @Test
    fun unmutedChatsAlwaysContributeToTheBadge() {
        val decision = decideNotification(
            payload(),
            NotificationPolicyState(badgeMuted = false),
            nowSeconds = 10,
            appInForeground = false,
        )
        assertTrue(decision.badge)
    }

    @Test
    fun openChatSuppressesSoundAndPopup() {
        val decision = decideNotification(payload(), NotificationPolicyState(), 10, true, openChatId = 7L)
        assertFalse(decision.show)
        assertFalse(decision.popup)
        assertEquals(false, decision.vibrate)
    }
}
