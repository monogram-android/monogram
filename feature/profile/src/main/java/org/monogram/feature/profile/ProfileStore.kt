package org.monogram.feature.profile

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.Outcome
import org.monogram.core.common.telegram.TelegramError
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.models.Chat
import org.monogram.core.models.Message
import org.monogram.core.models.PeerId
import org.monogram.core.models.Profile
import org.monogram.core.models.ProfileMember
import org.monogram.core.models.ProfileTab
import org.monogram.core.models.ProfileTabCounts
import org.monogram.network.bridge.MtprotoClient

/** A selectable section of the profile screen. */
sealed interface ProfilePanel {
    /** Group members or channel subscribers/administrators. */
    data object Members : ProfilePanel

    /** Photos/videos, files, links, GIFs, voice, or music. */
    data class SharedMedia(val tab: ProfileTab) : ProfilePanel

    /** Groups and channels shared with a user. */
    data object CommonGroups : ProfilePanel
}

interface ProfileStore : Store<ProfileStore.Intent, ProfileStore.State, Nothing> {
    sealed interface Intent {
        data object Refresh : Intent
        data class SelectPanel(val panel: ProfilePanel) : Intent
        data class LoadMore(val panel: ProfilePanel) : Intent
    }

    data class State(
        val peerId: PeerId,
        val profile: Profile? = null,
        val loading: Boolean = false,
        val error: TelegramError? = null,
        val tabCounts: ProfileTabCounts = ProfileTabCounts(),
        val panels: List<ProfilePanel> = emptyList(),
        val selectedPanel: ProfilePanel? = null,
        /** True once the user picks a panel, so automatic selection stops overriding it. */
        val explicitSelection: Boolean = false,
        val sharedMedia: Map<ProfileTab, List<Message>> = emptyMap(),
        val sharedMediaLoading: Set<ProfileTab> = emptySet(),
        val sharedMediaEnd: Set<ProfileTab> = emptySet(),
        val sharedMediaError: TelegramError? = null,
        val members: List<ProfileMember> = emptyList(),
        val membersTotal: Int = 0,
        val membersLoading: Boolean = false,
        val membersEnd: Boolean = false,
        /** `null` while unknown; `false` when the server refuses to list participants. */
        val membersAvailable: Boolean? = null,
        val commonChats: List<Chat> = emptyList(),
        val commonChatsCount: Int = 0,
        val commonChatsLoading: Boolean = false,
    )
}

internal class ProfileStoreFactory(
    private val storeFactory: StoreFactory,
    private val client: MtprotoClient,
    private val sessionStore: SessionMetadataStore?,
    private val peerId: PeerId,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun create(): ProfileStore =
        object :
            ProfileStore,
            Store<ProfileStore.Intent, ProfileStore.State, Nothing> by storeFactory.create(
                name = "ProfileStore",
                initialState = ProfileStore.State(peerId = peerId),
                bootstrapper = SimpleBootstrapper(Unit),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private sealed interface Msg {
        data class Loading(val value: Boolean) : Msg
        data class ProfileLoaded(val profile: Profile) : Msg
        data class Error(val value: TelegramError?) : Msg
        data class TabCounts(val value: ProfileTabCounts) : Msg
        data class PanelSelected(val panel: ProfilePanel, val explicit: Boolean = false) : Msg
        data class SharedMediaLoading(val tab: ProfileTab, val value: Boolean) : Msg
        data class SharedMediaPage(
            val tab: ProfileTab,
            val messages: List<Message>,
            val end: Boolean,
            val replace: Boolean = false,
        ) : Msg
        data class SharedMediaError(val value: TelegramError?) : Msg
        data class MembersLoading(val value: Boolean) : Msg
        data class MembersLoaded(
            val members: List<ProfileMember>,
            val total: Int,
            val appended: Boolean,
            val end: Boolean,
        ) : Msg
        data class MembersUnavailable(val value: Boolean) : Msg
        data class CommonChatsLoading(val value: Boolean) : Msg
        data class CommonChatsLoaded(val chats: List<Chat>, val total: Int) : Msg
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<ProfileStore.Intent, Unit, ProfileStore.State, Msg, Nothing>() {
        override fun executeAction(action: Unit) {
            load(initial = true)
        }

        override fun executeIntent(intent: ProfileStore.Intent) {
            when (intent) {
                ProfileStore.Intent.Refresh -> load(initial = false)
                is ProfileStore.Intent.SelectPanel -> select(intent.panel)
                is ProfileStore.Intent.LoadMore -> when (val panel = intent.panel) {
                    is ProfilePanel.SharedMedia -> loadMediaPage(panel.tab, reset = false)
                    ProfilePanel.Members -> loadMembers(reset = false)
                    ProfilePanel.CommonGroups -> Unit
                }
            }
        }

        private fun load(initial: Boolean) {
            dispatch(Msg.Error(null))
            scope.launch {
                val cachedProfile = if (initial) {
                    sessionStore?.readProfile(peerId.value)
                } else {
                    null
                }
                cachedProfile?.let { cached ->
                    dispatch(Msg.ProfileLoaded(cached))
                    sessionStore?.readProfileTabCounts(peerId.value)?.let { cachedCounts ->
                        dispatch(Msg.TabCounts(cachedCounts))
                    }
                    sessionStore?.readProfileMembers(peerId.value)?.let { cachedMembers ->
                        dispatch(
                            Msg.MembersLoaded(
                                members = cachedMembers.members,
                                total = cachedMembers.count,
                                appended = false,
                                end = cachedMembers.members.size < MEMBER_PAGE_SIZE,
                            ),
                        )
                    }
                    sessionStore?.readProfileMedia(peerId.value)?.forEach { (tab, messages) ->
                        if (messages.isNotEmpty()) {
                            dispatch(
                                Msg.SharedMediaPage(
                                    tab = tab,
                                    messages = messages,
                                    end = false,
                                    replace = true,
                                ),
                            )
                        }
                    }
                    sessionStore?.readProfileCommonChats(peerId.value)?.takeIf { it.isNotEmpty() }?.let { chats ->
                        dispatch(Msg.CommonChatsLoaded(chats, chats.size))
                    }
                }
                if (cachedProfile == null) dispatch(Msg.Loading(true))
                val result = client.getProfile(peerId)
                val profile = (result as? Outcome.Ok)?.value ?: cachedProfile
                if (profile == null) {
                    if (result is Outcome.Err) dispatch(Msg.Error(result.telegramError))
                    dispatch(Msg.Loading(false))
                    return@launch
                }
                if (result is Outcome.Ok) {
                    dispatch(Msg.ProfileLoaded(result.value))
                    withContext(ioDispatcher) { sessionStore?.upsertProfile(result.value) }
                }
                // Carry values locally: the reducer has not necessarily applied the
                // dispatches above by the time follow-up work starts.
                val counts = loadTabCounts()
                loadPanelsForPeer(profile, counts)
                dispatch(Msg.Loading(false))
            }
        }

        private suspend fun loadTabCounts(): ProfileTabCounts? {
            val result = client.getProfileTabCounts(peerId)
            return when (result) {
                is Outcome.Ok -> {
                    dispatch(Msg.TabCounts(result.value))
                    withContext(ioDispatcher) {
                        sessionStore?.saveProfileTabCounts(peerId.value, result.value)
                    }
                    result.value
                }
                is Outcome.Err -> null
            }
        }

        /**
         * Loads the member list for groups and for channels that allow it, plus the shared
         * groups of a user, then opens the first useful panel.
         */
        private fun loadPanelsForPeer(profile: Profile, counts: ProfileTabCounts?) {
            if (profile.kind == "chat" || profile.kind == "group" ||
                (profile.kind == "channel" && profile.canViewParticipants == true)
            ) {
                loadMembers(reset = true)
            }
            if (!profile.isSelf && !profile.isBot && (profile.commonChatsCount ?: 0) > 0) {
                loadCommonChats()
            }
            val membersPanel = profile.kind == "chat" || profile.kind == "group" ||
                (profile.kind == "channel" && profile.canViewParticipants == true)
            if (!membersPanel && counts != null) {
                counts.visibleTabs().firstOrNull()?.let { tab ->
                    loadMediaPage(tab, reset = true)
                }
            }
        }

        private fun select(panel: ProfilePanel) {
            dispatch(Msg.PanelSelected(panel, explicit = true))
            loadPanelData(panel)
        }

        /** Loads the data a panel needs, once. */
        private fun loadPanelData(panel: ProfilePanel) {
            when (panel) {
                is ProfilePanel.SharedMedia ->
                    if (state().sharedMedia[panel.tab] == null) loadMediaPage(panel.tab, reset = true)
                ProfilePanel.Members ->
                    if (state().members.isEmpty() && state().membersAvailable != false) {
                        loadMembers(reset = true)
                    }
                ProfilePanel.CommonGroups ->
                    if (state().commonChats.isEmpty()) loadCommonChats()
            }
        }

        private fun loadMediaPage(tab: ProfileTab, reset: Boolean) {
            if (state().sharedMediaLoading.contains(tab)) return
            val offset = if (reset) 0 else state().sharedMedia[tab]?.lastOrNull()?.id?.id ?: 0
            dispatch(Msg.SharedMediaLoading(tab, true))
            dispatch(Msg.SharedMediaError(null))
            scope.launch {
                val result = client.searchMessagesFiltered(
                    chatId = peerId,
                    query = "",
                    filter = tab.wire,
                    offsetId = offset,
                    addOffset = 0,
                    limit = MEDIA_PAGE_SIZE,
                )
                when (result) {
                    is Outcome.Ok -> {
                        val page = result.value.filter { it.id.id > 0 }
                        dispatch(
                            Msg.SharedMediaPage(
                                tab = tab,
                                messages = page,
                                end = page.isEmpty() || page.size < MEDIA_PAGE_SIZE,
                                replace = reset,
                            ),
                        )
                        if (reset) {
                            withContext(ioDispatcher) {
                                sessionStore?.saveProfileMedia(peerId.value, tab, page)
                            }
                        }
                    }
                    is Outcome.Err -> dispatch(Msg.SharedMediaError(result.telegramError))
                }
                dispatch(Msg.SharedMediaLoading(tab, false))
            }
        }

        private fun loadMembers(reset: Boolean) {
            if (state().membersLoading) return
            val offset = if (reset) 0 else state().members.size
            dispatch(Msg.MembersLoading(true))
            scope.launch {
                when (val result = client.getProfileMembers(peerId, "recent", "", offset, MEMBER_PAGE_SIZE)) {
                    is Outcome.Ok -> {
                        val page = result.value
                        dispatch(
                            Msg.MembersLoaded(
                                members = page.members,
                                total = page.count,
                                appended = !reset,
                                end = page.members.size < MEMBER_PAGE_SIZE,
                            ),
                        )
                        dispatch(Msg.MembersUnavailable(false))
                        if (reset) {
                            withContext(ioDispatcher) {
                                sessionStore?.saveProfileMembers(peerId.value, page)
                            }
                        }
                    }
                    is Outcome.Err -> {
                        dispatch(Msg.MembersLoading(false))
                        // Hide the panel only when Telegram forbids the list, not on a blip.
                        if (reset && state().members.isEmpty() && isParticipantsHidden(result.telegramError)) {
                            dispatch(Msg.MembersUnavailable(true))
                        }
                    }
                }
                dispatch(Msg.MembersLoading(false))
            }
        }

        private fun loadCommonChats() {
            if (state().commonChatsLoading) return
            dispatch(Msg.CommonChatsLoading(true))
            scope.launch {
                when (val result = client.getCommonChats(peerId, 0L, COMMON_CHATS_LIMIT)) {
                    is Outcome.Ok -> {
                        dispatch(
                            Msg.CommonChatsLoaded(
                                chats = result.value,
                                total = state().profile?.commonChatsCount ?: result.value.size,
                            ),
                        )
                        withContext(ioDispatcher) {
                            sessionStore?.saveProfileCommonChats(peerId.value, result.value)
                        }
                    }
                    is Outcome.Err -> Unit
                }
                dispatch(Msg.CommonChatsLoading(false))
            }
        }
    }

    private object ReducerImpl : Reducer<ProfileStore.State, Msg> {
        override fun ProfileStore.State.reduce(msg: Msg): ProfileStore.State = when (msg) {
            is Msg.Loading -> copy(loading = msg.value)
            is Msg.ProfileLoaded -> copy(profile = msg.profile, error = null).withPanels()
            is Msg.Error -> copy(error = msg.value)
            is Msg.TabCounts -> copy(tabCounts = tabCounts.merge(msg.value)).withPanels()
            is Msg.PanelSelected -> copy(
                selectedPanel = msg.panel,
                explicitSelection = explicitSelection || msg.explicit,
            )
            is Msg.SharedMediaLoading -> copy(
                sharedMediaLoading = if (msg.value) {
                    sharedMediaLoading + msg.tab
                } else {
                    sharedMediaLoading - msg.tab
                },
            )
            is Msg.SharedMediaPage -> copy(
                sharedMedia = sharedMedia + (
                    msg.tab to (
                        if (msg.replace) msg.messages else sharedMedia[msg.tab].orEmpty() + msg.messages
                        ).distinctBy { it.id.id }
                    ),
                sharedMediaEnd = if (msg.end) sharedMediaEnd + msg.tab else sharedMediaEnd - msg.tab,
                sharedMediaError = null,
            )
            is Msg.SharedMediaError -> copy(sharedMediaError = msg.value)
            is Msg.MembersLoading -> copy(membersLoading = msg.value)
            is Msg.MembersLoaded -> copy(
                members = if (msg.appended) {
                    (members + msg.members).distinctBy { it.id.value }
                } else {
                    msg.members
                },
                membersTotal = if (msg.total > 0) msg.total else msg.members.size,
                membersEnd = msg.end,
            ).withPanels()
            is Msg.MembersUnavailable -> copy(membersAvailable = if (msg.value) false else true)
                .withPanels()
            is Msg.CommonChatsLoading -> copy(commonChatsLoading = msg.value)
            is Msg.CommonChatsLoaded -> copy(
                commonChats = msg.chats,
                commonChatsCount = maxOf(msg.total, msg.chats.size),
            ).withPanels()
        }

        /**
         * Panels follow Telegram's strip: members first, then shared media tabs the server
         * reports as non-empty, then groups in common. A tab with an unknown count stays
         * visible until a known zero hides it.
         */
        private fun ProfileStore.State.withPanels(): ProfileStore.State {
            val profile = profile ?: return this
            val membersVisible = when (profile.kind) {
                // Telegram always offers the member list for groups, even before it loads.
                "chat", "group" -> membersAvailable != false
                "channel" -> profile.canViewParticipants == true && membersAvailable != false
                else -> false
            }
            val panels = buildList {
                if (membersVisible) add(ProfilePanel.Members)
                ProfileTab.entries.forEach { tab ->
                    if (tabCounts.isVisible(tab)) add(ProfilePanel.SharedMedia(tab))
                }
                val commonCount = maxOf(commonChatsCount, profile.commonChatsCount ?: 0, commonChats.size)
                if (!profile.isSelf && !profile.isBot && (commonCount > 0 || commonChats.isNotEmpty())) {
                    add(ProfilePanel.CommonGroups)
                }
            }
            val selected = when {
                explicitSelection && selectedPanel != null && selectedPanel in panels -> selectedPanel
                else -> panels.firstOrNull()
            }
            return copy(panels = panels, selectedPanel = selected)
        }
    }

    private companion object {
        const val MEDIA_PAGE_SIZE = 40
        const val MEMBER_PAGE_SIZE = 50
        const val COMMON_CHATS_LIMIT = 100
    }
}

private fun isParticipantsHidden(error: TelegramError): Boolean {
    if (error.kind == TelegramError.Kind.Privacy) return true
    val type = error.type.uppercase()
    return type.contains("ADMIN_REQUIRED") ||
        type.contains("CHANNEL_PRIVATE") ||
        type.contains("CHAT_FORBIDDEN") ||
        type.contains("PARTICIPANTS") && type.contains("HIDDEN")
}
