package org.monogram.feature.profile

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.monogram.core.common.Outcome
import org.monogram.core.models.AuthState
import org.monogram.core.models.Chat
import org.monogram.core.models.Folder
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileMemberPage
import org.monogram.core.models.ProfileTab
import org.monogram.core.models.ProfileTabCounts
import org.monogram.feature.profile.ui.ProfileMemberBadge
import org.monogram.feature.profile.ui.profileMemberBadge
import org.monogram.network.bridge.MtprotoClient
import org.monogram.network.bridge.MtprotoUpdate
import org.monogram.network.bridge.UpdatesCursor

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStorePanelsTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun emptyTabsAreHiddenAndUnknownTabsStayVisible() {
        val store = store(
            profile = profile(kind = "group"),
            counts = ProfileTabCounts(
                counts = mapOf(
                    ProfileTab.MEDIA to 5,
                    ProfileTab.FILES to 0,
                    ProfileTab.LINKS to 0,
                ),
            ),
            members = ProfileMemberPage(
                count = 2,
                members = listOf(member(1, role = ProfileMember.ROLE_OWNER)),
            ),
        )
        try {
            val panels = store.state.panels
            assertEquals(ProfilePanel.Members, panels.first())
            assertTrue(panels.contains(ProfilePanel.SharedMedia(ProfileTab.MEDIA)))
            // Known zero hides the tab even before the media is ever opened.
            assertFalse(panels.any { it == ProfilePanel.SharedMedia(ProfileTab.FILES) })
            assertFalse(panels.any { it == ProfilePanel.SharedMedia(ProfileTab.LINKS) })
            // Unanswered tabs stay visible until the server reports a zero.
            assertTrue(panels.any { it == ProfilePanel.SharedMedia(ProfileTab.GIFS) })
            assertTrue(panels.any { it == ProfilePanel.SharedMedia(ProfileTab.VOICE) })
            assertTrue(panels.any { it == ProfilePanel.SharedMedia(ProfileTab.MUSIC) })
        } finally {
            store.dispose()
        }
    }

    @Test
    fun channelWithoutParticipantVisibilityHidesMembersPanel() {
        val store = store(
            profile = profile(kind = "channel", canViewParticipants = false),
            counts = onlyMedia(),
            members = null,
        )
        try {
            assertEquals(listOf(ProfilePanel.SharedMedia(ProfileTab.MEDIA)), store.state.panels)
        } finally {
            store.dispose()
        }
    }

    @Test
    fun channelWithParticipantVisibilityKeepsMembersFirst() {
        val store = store(
            profile = profile(kind = "channel", canViewParticipants = true),
            counts = onlyMedia(),
            members = ProfileMemberPage(
                count = 10,
                members = listOf(member(2, role = ProfileMember.ROLE_ADMIN)),
            ),
        )
        try {
            assertEquals(
                listOf(ProfilePanel.Members, ProfilePanel.SharedMedia(ProfileTab.MEDIA)),
                store.state.panels,
            )
            assertEquals(ProfilePanel.Members, store.state.selectedPanel)
            assertEquals(ProfileMember.ROLE_ADMIN, store.state.members.first().role)
        } finally {
            store.dispose()
        }
    }

    private fun onlyMedia() = ProfileTabCounts(
        counts = mapOf(
            ProfileTab.MEDIA to 3,
            ProfileTab.FILES to 0,
            ProfileTab.LINKS to 0,
            ProfileTab.GIFS to 0,
            ProfileTab.VOICE to 0,
            ProfileTab.MUSIC to 0,
        ),
    )

    @Test
    fun commonGroupsPanelAppearsWhenCountOrListIsKnown() {
        val byCount = store(
            profile = profile(kind = "user", commonChatsCount = 4),
            counts = ProfileTabCounts(counts = mapOf(ProfileTab.MEDIA to 1)),
            members = null,
            commonChats = emptyList(),
        )
        try {
            assertTrue(byCount.state.panels.contains(ProfilePanel.CommonGroups))
        } finally {
            byCount.dispose()
        }

        val none = store(
            profile = profile(kind = "user", commonChatsCount = 0),
            counts = ProfileTabCounts(counts = mapOf(ProfileTab.MEDIA to 1)),
            members = null,
            commonChats = emptyList(),
        )
        try {
            assertFalse(none.state.panels.contains(ProfilePanel.CommonGroups))
        } finally {
            none.dispose()
        }
    }

    @Test
    fun badgePrefersOwnerThenAdminAndIgnoresPlainMembers() {
        assertEquals(
            ProfileMemberBadge.Owner,
            profileMemberBadge(member(1, role = ProfileMember.ROLE_OWNER)),
        )
        assertEquals(
            ProfileMemberBadge.Admin,
            profileMemberBadge(member(1, role = ProfileMember.ROLE_ADMIN)),
        )
        assertEquals(null, profileMemberBadge(member(1)))
    }

    private fun member(id: Long, role: String = ProfileMember.ROLE_MEMBER) = ProfileMember(
        id = PeerId(id),
        title = "Member $id",
        role = role,
    )

    private fun profile(
        kind: String,
        canViewParticipants: Boolean? = null,
        commonChatsCount: Int? = null,
    ) = Profile(
        id = PeerId(-100L),
        kind = kind,
        title = "Peer",
        membersCount = 10,
        commonChatsCount = commonChatsCount,
        canViewParticipants = canViewParticipants,
    )

    private fun store(
        profile: Profile,
        counts: ProfileTabCounts,
        members: ProfileMemberPage?,
        commonChats: List<Chat> = emptyList(),
    ): ProfileStore = ProfileStoreFactory(
        DefaultStoreFactory(),
        StubClient(profile = profile, counts = counts, members = members, commonChats = commonChats),
        sessionStore = null,
        peerId = PeerId(-100L),
    ).create()

    private class StubClient(
        private val profile: Profile,
        private val counts: ProfileTabCounts,
        private val members: ProfileMemberPage?,
        private val commonChats: List<Chat>,
    ) : MtprotoClient {
        override suspend fun connect() = Outcome.Ok(Unit)
        override suspend fun sendAuthCode(phone: String) = unused<AuthState.AwaitingCode>()
        override suspend fun signIn(phone: String, phoneCodeHash: String, code: String) = unused<AuthState>()
        override suspend fun checkPassword(password: String) = unused<AuthState.Authorized>()
        override suspend fun getChats() = Outcome.Ok(emptyList<Chat>())
        override suspend fun loadMoreChats(offsetDate: Int, offsetId: Int, offsetPeerId: Long) =
            Outcome.Ok(emptyList<Chat>())
        override suspend fun getFolders() = Outcome.Ok(emptyList<Folder>())
        override suspend fun getHistory(chatId: PeerId, limit: Int) = Outcome.Ok(emptyList<Message>())
        override suspend fun getHistoryPage(
            chatId: PeerId,
            limit: Int,
            offsetId: Int,
            offsetDate: Int,
            addOffset: Int,
        ) = Outcome.Ok(emptyList<Message>())
        override suspend fun searchMessages(chatId: PeerId, query: String, limit: Int) =
            Outcome.Ok(emptyList<Message>())
        override suspend fun getPinnedMessages(chatId: PeerId, limit: Int) = Outcome.Ok(emptyList<Message>())
        override suspend fun sendText(
            chatId: PeerId,
            text: String,
            replyToMsgId: Int,
            entitiesJson: String?,
            topMsgId: Int,
        ) = unused<Message>()
        override suspend fun sendPhoto(
            chatId: PeerId,
            path: String,
            caption: String,
            replyToMsgId: Int,
            topMsgId: Int,
            entitiesJson: String?,
        ) = unused<Message>()
        override suspend fun editText(chatId: PeerId, messageId: Int, text: String, entitiesJson: String?) =
            unused<Message>()
        override suspend fun deleteMessage(chatId: PeerId, messageId: Int, revoke: Boolean) = Outcome.Ok(Unit)
        override suspend fun forwardMessage(fromChatId: PeerId, messageId: Int, toChatId: PeerId) =
            Outcome.Ok(emptyList<Message>())
        override suspend fun readHistory(chatId: PeerId, maxId: Int) = Outcome.Ok(Unit)
        override suspend fun setTyping(chatId: PeerId, typing: Boolean) = Outcome.Ok(Unit)
        override suspend fun getProfile(peerId: PeerId) = Outcome.Ok(profile)
        override suspend fun getProfileTabCounts(
            peerId: PeerId,
            tabs: List<ProfileTab>,
        ) = Outcome.Ok(counts)
        override suspend fun getProfileMembers(
            peerId: PeerId,
            filter: String,
            query: String,
            offset: Int,
            limit: Int,
        ) = members?.let { Outcome.Ok(it) } ?: Outcome.Err("no members")
        override suspend fun getCommonChats(userId: PeerId, maxId: Long, limit: Int) =
            Outcome.Ok(commonChats)
        override suspend fun downloadMessageMedia(chatId: PeerId, messageId: Int, destPath: String) =
            unused<String>()
        override suspend fun downloadMessageThumb(chatId: PeerId, messageId: Int, destPath: String) =
            unused<String>()
        override suspend fun getUpdatesState() = Outcome.Ok(UpdatesCursor(0, 0, 0, 0))
        override fun updates(): Flow<MtprotoUpdate> = MutableSharedFlow()
        override fun sessionLost(): Flow<Unit> = emptyFlow()
        override fun libraryVersion() = "test"
        override suspend fun logout() = Outcome.Ok(Unit)
        override fun close() = Unit
        private fun <T> unused(): Outcome<T> = Outcome.Err("unused")
    }
}
