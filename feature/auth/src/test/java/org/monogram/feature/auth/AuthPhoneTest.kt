package org.monogram.feature.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthPhoneTest {
    @Test
    fun emptyAndBarePlusAreBlank() {
        assertEquals("", normalizePhone("   "))
        assertEquals("", normalizePhone("+"))
        assertEquals("", normalizePhone(" + "))
    }

    @Test
    fun addsPlusAndDropsSpaces() {
        assertEquals("+491511234567", normalizePhone("49 151 1234567"))
        assertEquals("+491511234567", normalizePhone("+49 151 1234567"))
        assertEquals("+12025550123", normalizePhone("+1 202 555 0123"))
    }
}
