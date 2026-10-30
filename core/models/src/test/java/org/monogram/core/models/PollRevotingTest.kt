package org.monogram.core.models

import org.junit.Assert.*
import org.junit.Test

class PollRevotingTest {
    private fun parse(field: String): Poll = ServiceMedia.poll("""{"q":"Fixture","a":[]$field}""")!!

    @Test
    fun pollIdentityUsesLosslessDecimalStringAndLegacyPayloadHasNoIdentity() {
        assertNull(parse("").id)
        assertEquals(Long.MAX_VALUE, parse(",\"id\":\"9223372036854775807\"").id)
        assertEquals(Long.MIN_VALUE, parse(",\"id\":\"-9223372036854775808\"").id)
        assertNull(parse(",\"id\":\"invalid\"").id)
    }

    @Test
    fun creatorPermissionIsExplicitAndAbsentForLegacyPayloads() {
        assertFalse(parse("").creator)
        assertTrue(parse(",\"creator\":1").creator)
        assertTrue(parse(",\"creator\":true").creator)
        assertFalse(parse(",\"creator\":0").creator)
    }

    @Test
    fun legacyPollsKeepRevotingAndCurrentPayloadAcceptsBooleanOrIntegerFlag() {
        assertFalse(parse("").revotingDisabled)
        assertFalse(parse(",\"r\":0").revotingDisabled)
        assertFalse(parse(",\"r\":false").revotingDisabled)
        assertTrue(parse(",\"r\":1").revotingDisabled)
        assertTrue(parse(",\"r\":true").revotingDisabled)
    }
}
