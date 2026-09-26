package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExtrasTest {
    @Test
    fun parseCountsPhoneAndBadges() {
        val extra = ProfileExtras.parse(
            """{"members":12,"online":3,"common":4,"phone":"+1","bot":true,"verified":true,"scam":false,"premium":true}""",
        )
        assertEquals(12, extra.membersCount)
        assertEquals(3, extra.onlineCount)
        assertEquals(4, extra.commonChatsCount)
        assertEquals("+1", extra.phone)
        assertTrue(extra.isBot)
        assertTrue(extra.isVerified)
        assertFalse(extra.isScam)
        assertTrue(extra.isPremium)
    }

    @Test
    fun parseEmpty() {
        val extra = ProfileExtras.parse(null)
        assertEquals(null, extra.membersCount)
        assertFalse(extra.isBot)
    }

    @Test
    fun explicitNullAndAbsentOptionalFieldsBehaveTheSame() {
        assertEquals(null, ProfileExtras.parse("""{"phone":null}""").phone)
        assertEquals(null, ProfileExtras.parse("""{"members":1}""").phone)
        assertEquals(null, ProfileExtras.parse("""{"phone":""}""").phone)
        assertEquals(null, ProfileExtras.parse("""{"participants":null}""").canViewParticipants)
        assertEquals(null, ProfileExtras.parse("""{"members":1}""").canViewParticipants)
        assertEquals(true, ProfileExtras.parse("""{"participants":true}""").canViewParticipants)
        assertEquals(false, ProfileExtras.parse("""{"participants":false}""").canViewParticipants)
    }

    @Test
    fun serializeRoundtripsFullProfile() {
        val extra = ProfileExtra(
            phone = "+1",
            membersCount = 12,
            isBot = true,
            isPremium = true,
        )
        val parsed = ProfileExtras.parse(ProfileExtras.serialize(extra))
        assertEquals("+1", parsed.phone)
        assertEquals(12, parsed.membersCount)
        assertTrue(parsed.isBot)
        assertTrue(parsed.isPremium)
        assertFalse(parsed.isVerified)
    }

    @Test
    fun mergeFullKeepsPriorPhoneAndAppliesIncomingPremium() {
        val merged = ProfileExtras.mergeFull(
            ProfileExtra(phone = "+1", isPremium = true, membersCount = 9),
            ProfileExtra(isPremium = false, membersCount = 10),
        )
        assertEquals("+1", merged.phone)
        assertEquals(10, merged.membersCount)
        assertFalse(merged.isPremium)
    }
}
