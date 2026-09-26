package org.monogram.core.common.push

/**
 * Rate limit for notification alerts. A burst of pushes for one chat should update the collapsed
 * notification without beeping once per message; the first alert always plays, later ones inside
 * [WINDOW_MILLIS] are posted silently.
 */
object NotificationAlertThrottle {
    const val WINDOW_MILLIS: Long = 500

    /** Whether an alert may be played at [nowMillis] given the previous alert time (0 = never). */
    fun shouldAlert(lastAlertAtMillis: Long, nowMillis: Long, windowMillis: Long = WINDOW_MILLIS): Boolean =
        lastAlertAtMillis <= 0L || nowMillis - lastAlertAtMillis >= windowMillis
}
