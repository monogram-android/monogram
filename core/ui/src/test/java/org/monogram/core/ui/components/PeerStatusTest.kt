package org.monogram.core.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerStatusTest {
    @Test
    fun onlineLeaseIsOnlyValidUntilExpiry() {
        assertTrue(isPeerOnline("online", 200L, nowMillis = 100_000L))
        assertFalse(isPeerOnline("online", 90L, nowMillis = 100_000L))
        assertFalse(isPeerOnline("offline", 90L, nowMillis = 100_000L))
        assertFalse(isPeerOnline("recently", null, nowMillis = 100_000L))
        assertFalse(isPeerOnline(null, null, nowMillis = 100_000L))
    }

    @Test
    fun undatedOnlineHasNoLeaseToExpire() {
        assertTrue(isPeerOnline("online", null, nowMillis = 100_000L))
    }
}
