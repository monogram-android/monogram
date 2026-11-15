package org.monogram

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.AuthSession
import org.monogram.core.models.PeerId
import org.monogram.root.recoveredProfileSession

class StartupAuthTest {
    @Test
    fun explicitSignOutTakesPrecedenceOverNativeAuthorization() = runBlocking {
        var nativeCalled = false
        val home = resolveStartOnHome(
            sessionAuthorized = { true },
            locallyAuthorized = { nativeCalled = true; Outcome.Ok(true) },
            explicitlySignedOut = { true },
        )
        assertFalse(home)
        assertFalse(nativeCalled)
    }

    @Test
    fun profileRecoveryRequiresMatchingAccountAndValidDataCenter() {
        val user = PeerId(10L)
        assertEquals(AuthSession(user, 2), recoveredProfileSession(user, 10L, 2))
        assertNull(recoveredProfileSession(user, 11L, 2))
        assertNull(recoveredProfileSession(user, 10L, 0))
        assertNull(recoveredProfileSession(user, null, 2))
    }

    @Test
    fun missingSignOutMarkerStillAllowsExistingLocalSession() = runBlocking {
        assertTrue(
            resolveStartOnHome(
                sessionAuthorized = { false },
                locallyAuthorized = { Outcome.Ok(true) },
            )
        )
    }
}
