package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationBadgePlanTest {
    @Test
    fun countsMessagesWhenEnabled() {
        assertEquals(4, NotificationBadgePlan.number(4, BadgeSettings()))
        assertEquals(0, NotificationBadgePlan.number(0, BadgeSettings()))
    }

    @Test
    fun countsChatsWhenMessageCountingIsOff() {
        val chats = BadgeSettings(countMessages = false)
        assertEquals(1, NotificationBadgePlan.number(1, chats))
        assertEquals(1, NotificationBadgePlan.number(37, chats))
        assertEquals(0, NotificationBadgePlan.number(0, chats))
    }

    @Test
    fun disabledBadgeNeverExposesACount() {
        val off = BadgeSettings(enabled = false)
        assertEquals(0, NotificationBadgePlan.number(9, off))
        assertEquals(0, NotificationBadgePlan.number(9, BadgeSettings(enabled = false, countMessages = false)))
    }
}
