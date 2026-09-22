package org.monogram.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import org.monogram.core.models.AuthState
import org.monogram.feature.auth.ui.AuthCodeLength

class AuthStoreCodeLengthTest {

    @Test
    fun defaultCodeLengthIsFive() {
        val defaultState = AuthState.AwaitingCode(
            phone = "+12025550123",
            phoneCodeHash = "hash1",
        )
        assertEquals(5, defaultState.codeLength)

        val defaultPhase = AuthStore.Phase.CodeEntry(
            phone = "+12025550123",
            phoneCodeHash = "hash1",
        )
        assertEquals(5, defaultPhase.codeLength)
        assertEquals(AuthCodeLength, defaultPhase.codeLength)
    }

    @Test
    fun dynamicCodeLengthPreserved() {
        val awaitingSix = AuthState.AwaitingCode(
            phone = "+12025550123",
            phoneCodeHash = "hash1",
            codeType = "app",
            codeLength = 6,
        )
        assertEquals(6, awaitingSix.codeLength)

        val phaseSix = AuthStore.Phase.CodeEntry(
            phone = awaitingSix.phone,
            phoneCodeHash = awaitingSix.phoneCodeHash,
            codeType = awaitingSix.codeType,
            codeLength = awaitingSix.codeLength,
        )
        assertEquals(6, phaseSix.codeLength)
    }

    @Test
    fun codeFilteringRespectsExpectedCodeLength() {
        val input = "1720984"

        val length5 = 5
        val filtered5 = input.filter(Char::isDigit).take(length5)
        assertEquals("17209", filtered5)
        assertEquals(5, filtered5.length)

        val length6 = 6
        val filtered6 = input.filter(Char::isDigit).take(length6)
        assertEquals("172098", filtered6)
        assertEquals(6, filtered6.length)
    }
}
