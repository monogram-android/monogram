package org.monogram.core.common.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Channel plan used by `NotificationChannels.channelFor`: Android decides sound and heads-up
 * peeking from the channel, so the behaviour has to map to a stable variant id.
 */
class NotificationChannelPlanTest {
    @Test
    fun defaultBehaviourUsesTheCategoryChannel() {
        assertEquals("", NotificationChannelPlan.suffix(sound = true, popup = true))
        assertEquals(NotificationChannelPlan.Importance.High, NotificationChannelPlan.importance(true, true))
    }

    @Test
    fun disablingPopupNeverKeepsHeadsUpImportance() {
        assertEquals(NotificationChannelPlan.NO_POPUP, NotificationChannelPlan.suffix(sound = true, popup = false))
        assertEquals(NotificationChannelPlan.Importance.Default, NotificationChannelPlan.importance(true, false))
        assertNotEquals(NotificationChannelPlan.Importance.High, NotificationChannelPlan.importance(false, false))
        assertEquals(NotificationChannelPlan.Importance.Low, NotificationChannelPlan.importance(false, false))
    }

    @Test
    fun silentPopupKeepsHeadsUpImportance() {
        assertEquals(NotificationChannelPlan.SILENT, NotificationChannelPlan.suffix(sound = false, popup = true))
        assertEquals(NotificationChannelPlan.Importance.High, NotificationChannelPlan.importance(false, true))
    }

    @Test
    fun everyCombinationHasItsOwnStableVariant() {
        val combos = listOf(true to true, true to false, false to true, false to false)
        val ids = combos.map { (sound, popup) -> NotificationChannelPlan.suffix(sound, popup) }
        assertEquals(4, ids.toSet().size)
        val again = combos.map { (sound, popup) -> NotificationChannelPlan.suffix(sound, popup) }
        assertEquals(ids, again)
        assertEquals(NotificationChannelPlan.SILENT_NO_POPUP, NotificationChannelPlan.suffix(false, false))
    }
}
