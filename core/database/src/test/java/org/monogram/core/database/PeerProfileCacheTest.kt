package org.monogram.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileTab
import org.monogram.core.models.ProfileTabCounts

class PeerProfileCacheTest {
    @Test
    fun fullProfileRoundtripsExtrasAndSelf() {
        val profile = Profile(
            id = PeerId(7),
            kind = "user",
            title = "Ada",
            username = "ada",
            about = "bio",
            avatarCacheKey = "avatar:7",
            isSelf = true,
            phone = "+1",
            membersCount = 12,
            onlineCount = 3,
            isBot = true,
            isVerified = true,
            isPremium = true,
            emojiStatusDocumentId = 99L,
        )
        val stored = profile.toPeerEntity(null)
        val restored = stored.toProfile()
        assertEquals("Ada", restored.title)
        assertEquals("ada", restored.username)
        assertEquals("bio", restored.about)
        assertEquals("avatar:7", restored.avatarCacheKey)
        assertTrue(restored.isSelf)
        assertEquals("+1", restored.phone)
        assertEquals(12, restored.membersCount)
        assertEquals(3, restored.onlineCount)
        assertTrue(restored.isBot)
        assertTrue(restored.isVerified)
        assertTrue(restored.isPremium)
        assertEquals(99L, restored.emojiStatusDocumentId)
    }

    @Test
    fun upsertPeerMinsStyleCopyKeepsExtras() {
        val full = Profile(
            id = PeerId(8),
            kind = "user",
            title = "Bob",
            phone = "+2",
            isPremium = true,
            isSelf = true,
            avatarCacheKey = "avatar:8",
        ).toPeerEntity(null)
        val afterMin = full.copy(
            title = "Bobby",
            emojiStatusDocumentId = 3L,
        )
        val restored = afterMin.toProfile()
        assertEquals("Bobby", restored.title)
        assertEquals("+2", restored.phone)
        assertTrue(restored.isPremium)
        assertTrue(restored.isSelf)
        assertEquals("avatar:8", restored.avatarCacheKey)
        assertEquals(3L, restored.emojiStatusDocumentId)
    }

    @Test
    fun fullUpsertKeepsPriorPhoneWhenIncomingOmitsIt() {
        val prior = Profile(
            id = PeerId(9),
            kind = "user",
            title = "Cara",
            phone = "+3",
            isPremium = true,
        ).toPeerEntity(null)
        val incoming = Profile(
            id = PeerId(9),
            kind = "user",
            title = "Cara",
            isPremium = false,
        )
        val merged = incoming.toPeerEntity(prior).toProfile()
        assertEquals("+3", merged.phone)
        assertFalse(merged.isPremium)
        assertFalse(merged.isSelf)
    }

    @Test
    fun draftKeysArePerChatAndThread() {
        assertEquals("draft:5", draftMetaKey(5))
        assertEquals("draft:5:12", draftMetaKey(5, 12))
    }

    @Test
    fun profileMemberEntityRoundTripsRoleAndRank() {
        val member = ProfileMember(
            id = PeerId(42),
            title = "Helper",
            username = "helper",
            avatarCacheKey = "avatar:42",
            status = "online",
            statusAt = 7L,
            role = ProfileMember.ROLE_ADMIN,
            rank = "Rank",
        )
        val restored = member.toEntity(peerId = 5L, position = 0).toModel()
        assertEquals(member, restored)
        assertEquals(ProfileMember.ROLE_OWNER, ProfileMember(
            id = PeerId(1),
            title = "Owner",
            role = ProfileMember.ROLE_OWNER,
        ).role)
    }

    @Test
    fun memberPagePreservesServerTotalOnCachedRows() {
        val member = ProfileMember(
            id = PeerId(42),
            title = "Helper",
            role = ProfileMember.ROLE_ADMIN,
        )
        val stored = member.toEntity(peerId = 5L, position = 0, totalCount = 66)
        assertEquals(66, stored.totalCount)
        assertEquals(member, stored.toModel())
    }

    @Test
    fun tabCountsSerializeKeepsKnownZeroAndUnknownTabs() {
        val counts = ProfileTabCounts(
            counts = mapOf(ProfileTab.MEDIA to 5, ProfileTab.FILES to 0),
        )
        val restored = ProfileTabCounts.parse(counts.serialize())
        assertEquals(5, restored.known(ProfileTab.MEDIA))
        assertEquals(0, restored.known(ProfileTab.FILES))
        assertTrue(restored.isVisible(ProfileTab.MEDIA))
        assertFalse(restored.isVisible(ProfileTab.FILES))
        assertNull(restored.known(ProfileTab.GIFS))
        assertTrue(restored.isVisible(ProfileTab.GIFS))
    }
}
