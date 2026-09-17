package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTabsTest {
    @Test
    fun unknownTabStaysVisibleUntilKnownZero() {
        val unknown = ProfileTabCounts()
        assertTrue(unknown.isVisible(ProfileTab.MEDIA))
        assertNull(unknown.known(ProfileTab.MEDIA))

        val empty = ProfileTabCounts(counts = mapOf(ProfileTab.MEDIA to 0))
        assertFalse(empty.isVisible(ProfileTab.MEDIA))
        assertTrue(empty.isVisible(ProfileTab.FILES))

        val some = ProfileTabCounts(counts = mapOf(ProfileTab.MEDIA to 3))
        assertTrue(some.isVisible(ProfileTab.MEDIA))
    }

    @Test
    fun serializeRoundTripsCountsAndInexactFlags() {
        val counts = ProfileTabCounts(
            counts = mapOf(
                ProfileTab.MEDIA to 12,
                ProfileTab.FILES to 0,
                ProfileTab.GIFS to 4,
            ),
            inexact = setOf(ProfileTab.MEDIA),
        )
        val encoded = counts.serialize()
        assertEquals(
            ProfileTabCounts(
                counts = mapOf(
                    ProfileTab.MEDIA to 12,
                    ProfileTab.FILES to 0,
                    ProfileTab.GIFS to 4,
                ),
                inexact = setOf(ProfileTab.MEDIA),
            ),
            ProfileTabCounts.parse(encoded),
        )
        assertNull(ProfileTabCounts().serialize())
        assertEquals(ProfileTabCounts(), ProfileTabCounts.parse(null))
        assertEquals(ProfileTabCounts(), ProfileTabCounts.parse("garbage;=4;unknown=9"))
    }

    @Test
    fun wireNamesMatchMessageFilters() {
        assertEquals("photo_video", ProfileTab.MEDIA.wire)
        assertEquals("document", ProfileTab.FILES.wire)
        assertEquals("url", ProfileTab.LINKS.wire)
        assertEquals("gif", ProfileTab.GIFS.wire)
        assertEquals("voice", ProfileTab.VOICE.wire)
        assertEquals("music", ProfileTab.MUSIC.wire)
        assertEquals(ProfileTab.GIFS, ProfileTab.fromWire("gif"))
        assertNull(ProfileTab.fromWire("polls"))
    }

    @Test
    fun memberBadgeReflectsRoleAndCustomRank() {
        assertEquals(
            true,
            ProfileMember(id = PeerId(1), title = "A", role = ProfileMember.ROLE_OWNER).hasBadge,
        )
        assertEquals(
            true,
            ProfileMember(id = PeerId(1), title = "A", role = ProfileMember.ROLE_ADMIN).hasBadge,
        )
        assertEquals(
            true,
            ProfileMember(id = PeerId(1), title = "A", rank = "Helper").hasBadge,
        )
        assertEquals(
            false,
            ProfileMember(id = PeerId(1), title = "A").hasBadge,
        )
    }
}
