package org.monogram.core.common.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlertThrottleTest {
    @Test
    fun firstAlertAlwaysPlays() {
        assertTrue(NotificationAlertThrottle.shouldAlert(lastAlertAtMillis = 0L, nowMillis = 1_000L))
    }

    @Test
    fun burstInsideTheWindowIsSilenced() {
        val last = 10_000L
        assertFalse(NotificationAlertThrottle.shouldAlert(last, nowMillis = last + 1))
        assertFalse(NotificationAlertThrottle.shouldAlert(last, nowMillis = last + 499))
    }

    @Test
    fun windowBoundaryAndLaterAlertsPlay() {
        val last = 10_000L
        assertTrue(NotificationAlertThrottle.shouldAlert(last, nowMillis = last + NotificationAlertThrottle.WINDOW_MILLIS))
        assertTrue(NotificationAlertThrottle.shouldAlert(last, nowMillis = last + 5_000))
    }

    @Test
    fun windowIsConfigurableForTests() {
        val last = 500L
        assertTrue(NotificationAlertThrottle.shouldAlert(last, nowMillis = last + 100, windowMillis = 50))
        assertFalse(NotificationAlertThrottle.shouldAlert(last, nowMillis = last + 100, windowMillis = 500))
    }
}
