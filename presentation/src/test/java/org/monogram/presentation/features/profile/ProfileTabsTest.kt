package org.monogram.presentation.features.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTabsTest {
    @Test
    fun `buildProfileTabSpecs injects member moderation tabs only when enabled`() {
        val tabs = buildProfileTabSpecs(
            isGroupOrChannel = true,
            preferredTabKey = ProfileTabKey.RESTRICTED,
            showMembers = true,
            showAdministrators = true,
            showRestricted = true,
            showBanned = false
        )

        assertTrue(tabs.any { it.key == ProfileTabKey.MEMBERS })
        assertTrue(tabs.any { it.key == ProfileTabKey.ADMINS })
        assertTrue(tabs.any { it.key == ProfileTabKey.RESTRICTED && it.initiallySelected })
        assertFalse(tabs.any { it.key == ProfileTabKey.BANNED })
    }

    @Test
    fun `private profile strips member tabs`() {
        val tabs = buildProfileTabSpecs(
            isGroupOrChannel = false,
            preferredTabKey = ProfileTabKey.ADMINS,
            showMembers = true,
            showAdministrators = true,
            showRestricted = true,
            showBanned = true
        )

        assertFalse(tabs.any { it.key.isMemberTab() })
        assertEquals(ProfileTabKey.STORIES, tabs.first { it.initiallySelected }.key)
    }

    @Test
    fun `initial visible tabs keep media anchor and member moderation tabs`() {
        val supportedTabs = buildProfileTabSpecs(
            isGroupOrChannel = true,
            preferredTabKey = null,
            showMembers = true,
            showAdministrators = true,
            showRestricted = true,
            showBanned = true
        )

        val visibleTabs = buildInitialVisibleProfileTabSpecs(
            supportedTabs = supportedTabs,
            preferredTabKey = null
        )

        assertTrue(visibleTabs.any { it.key == ProfileTabKey.STORIES })
        assertTrue(visibleTabs.any { it.key == ProfileTabKey.MEDIA })
        assertTrue(visibleTabs.any { it.key == ProfileTabKey.MEMBERS })
        assertTrue(visibleTabs.any { it.key == ProfileTabKey.ADMINS })
        assertTrue(visibleTabs.any { it.key == ProfileTabKey.RESTRICTED })
        assertTrue(visibleTabs.any { it.key == ProfileTabKey.BANNED })
        assertFalse(visibleTabs.any { it.key == ProfileTabKey.FILES })
        assertFalse(visibleTabs.any { it.key == ProfileTabKey.GIFS })
    }

    @Test
    fun `initial visible tabs preserve preferred media tab`() {
        val supportedTabs = buildProfileTabSpecs(
            isGroupOrChannel = true,
            preferredTabKey = ProfileTabKey.LINKS,
            showMembers = false
        )

        val visibleTabs = buildInitialVisibleProfileTabSpecs(
            supportedTabs = supportedTabs,
            preferredTabKey = ProfileTabKey.LINKS
        )

        assertTrue(visibleTabs.any { it.key == ProfileTabKey.MEDIA })
        assertTrue(visibleTabs.any { it.key == ProfileTabKey.LINKS })
        assertFalse(visibleTabs.any { it.key == ProfileTabKey.FILES })
        assertFalse(visibleTabs.any { it.key == ProfileTabKey.MUSIC })
    }
}
