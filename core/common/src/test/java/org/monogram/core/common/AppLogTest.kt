package org.monogram.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test
    fun redactStripsSecretLabels() {
        val raw = "api_hash=deadbeef password=secret phone_code=12345 auth_key=aabb"
        val redacted = AppLog.redact(raw)
        assertFalse(redacted.contains("deadbeef"))
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("12345"))
        assertFalse(redacted.contains("aabb"))
        assertTrue(redacted.contains("[redacted]"))
    }
}
