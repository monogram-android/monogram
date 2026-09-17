package org.monogram.core.common.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushWakePolicyTest {
    @Test
    fun wakeActionsDoNotShowBanners() {
        val wake = parsePushPayload("""{"loc_key":"WAKE"}""")
        val muted = parsePushPayload("""{"loc_key":"MESSAGE_MUTED"}""")
        val decision = decideNotification(wake, NotificationPolicyState(), 1, false)
        assertFalse(decision.show)
        assertFalse(decideNotification(muted, NotificationPolicyState(), 1, false).show)
    }

    @Test
    fun peerExceptionMuteSuppressesBanner() {
        val payload = parsePushPayload("""{"loc_key":"MESSAGE_TEXT","custom":{"from_id":9}}""")
        val muted = decideNotification(
            payload,
            NotificationPolicyState(
                exceptions = mapOf(9L to org.monogram.core.models.NotifySettings(muteUntil = Int.MAX_VALUE)),
            ),
            nowSeconds = 1,
            appInForeground = false,
        )
        assertFalse(muted.show)
    }

    @Test
    fun wakeGateCoalescesAndCancelInvalidatesInFlight() {
        val gate = PushWakeGate(minGapMs = 1_500L)
        val first = gate.tryStart(0L)
        assertTrue(first != null && gate.isCurrent(first))
        assertTrue(gate.tryStart(1_000L) == null)
        gate.cancel()
        assertFalse(gate.isCurrent(first!!))
        val second = gate.tryStart(2_000L)
        assertTrue(second != null && gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
    }

    @Test
    fun sessionRevokeIsNotAVisibleMessage() {
        assertTrue(actionFor("SESSION_REVOKE") == PushAction.SessionRevoke)
        assertFalse(
            decideNotification(
                parsePushPayload("""{"loc_key":"SESSION_REVOKE"}"""),
                NotificationPolicyState(),
                1,
                false,
            ).show,
        )
    }
}
