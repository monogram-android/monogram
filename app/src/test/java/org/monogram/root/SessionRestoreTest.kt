package org.monogram.root

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError

class SessionRestoreTest {
    @Test
    fun networkFailureKeepsCachedAccount() {
        assertFalse(requiresSessionReset(Outcome.Err("RPC timeout")))
        assertFalse(requiresSessionReset(Outcome.Ok(true)))
    }

    @Test
    fun absentOrRevokedAuthorizationClearsAccount() {
        assertTrue(requiresSessionReset(Outcome.Ok(false)))
        for (raw in listOf("RPC 406: AUTH_KEY_DUPLICATED", "RPC 401: SESSION_REVOKED")) {
            assertTrue(requiresSessionReset(Outcome.Err(raw, telegram = TelegramError.parse(raw))))
        }
    }
}
