package org.monogram.presentation.features.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.ChatFullInfoModel
import org.monogram.domain.models.ChatModel

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

    @Test
    fun `members tab is hidden for channels when current user is not admin`() {
        assertFalse(
            shouldShowMembersTab(
                chat = ChatModel(
                    id = 1L,
                    title = "Channel",
                    unreadCount = 0,
                    isChannel = true,
                    isAdmin = false
                ),
                fullInfo = ChatFullInfoModel(canGetMembers = true, memberCount = 120),
                resolvedMemberCount = 120
            )
        )
    }

    @Test
    fun `members tab is shown for channels when current user is admin`() {
        assertTrue(
            shouldShowMembersTab(
                chat = ChatModel(
                    id = 1L,
                    title = "Channel",
                    unreadCount = 0,
                    isChannel = true,
                    isAdmin = true
                ),
                fullInfo = ChatFullInfoModel(canGetMembers = true, memberCount = 120),
                resolvedMemberCount = 120
            )
        )
    }

    @Test
    fun `members tab is hidden for groups with hidden members when current user is not admin`() {
        assertFalse(
            shouldShowMembersTab(
                chat = ChatModel(
                    id = 2L,
                    title = "Group",
                    unreadCount = 0,
                    isGroup = true,
                    isAdmin = false
                ),
                fullInfo = ChatFullInfoModel(
                    canGetMembers = true,
                    memberCount = 50,
                    hasHiddenMembers = true
                ),
                resolvedMemberCount = 50
            )
        )
    }

    @Test
    fun `members tab is shown for groups with hidden members when current user is admin`() {
        assertTrue(
            shouldShowMembersTab(
                chat = ChatModel(
                    id = 2L,
                    title = "Group",
                    unreadCount = 0,
                    isGroup = true,
                    isAdmin = true
                ),
                fullInfo = ChatFullInfoModel(
                    canGetMembers = true,
                    memberCount = 50,
                    hasHiddenMembers = true
                ),
                resolvedMemberCount = 50
            )
        )
    }

    @Test
    fun `admins tab stays visible for admin even when administrator count is unavailable`() {
        assertTrue(
            shouldShowAdminsTab(
                chat = ChatModel(
                    id = 3L,
                    title = "Group",
                    unreadCount = 0,
                    isGroup = true,
                    isAdmin = true
                ),
                fullInfo = ChatFullInfoModel(administratorCount = 0)
            )
        )
    }

    @Test
    fun `moderation tabs are hidden for non admin users`() {
        assertFalse(
            shouldShowModerationTab(
                chat = ChatModel(
                    id = 4L,
                    title = "Group",
                    unreadCount = 0,
                    isGroup = true,
                    isAdmin = false
                ),
                memberCount = 3
            )
        )
    }

    @Test
    fun `moderation tabs are shown for admins when members exist`() {
        assertTrue(
            shouldShowModerationTab(
                chat = ChatModel(
                    id = 4L,
                    title = "Group",
                    unreadCount = 0,
                    isGroup = true,
                    isAdmin = true
                ),
                memberCount = 3
            )
        )
    }
}
