package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.domain.repository.ChatMembersFilter
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultMemberListComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val type: MemberListComponent.MemberListType,
    private val onBackClicked: () -> Unit,
    private val onMemberClicked: (Long) -> Unit,
    private val onMemberLongClicked: (Long) -> Unit
) : MemberListComponent, AppComponentContext by context {

    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository

    private val scope = componentScope
    private val _state = MutableValue(MemberListComponent.State(chatId = chatId, type = type))
    override val state: Value<MemberListComponent.State> = _state

    private var offset = 0
    private val limit = 50
    private var searchJob: Job? = null

    init {
        loadMembers()
    }

    private fun loadMembers() {
        if (_state.value.isLoading || !_state.value.canLoadMore || _state.value.isSearchActive) return

        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val filter = when (type) {
                    MemberListComponent.MemberListType.ADMINS -> ChatMembersFilter.Administrators
                    MemberListComponent.MemberListType.MEMBERS -> ChatMembersFilter.Recent
                    MemberListComponent.MemberListType.BLACKLIST -> ChatMembersFilter.Banned
                }

                val members = chatInfoRepository.getChatMembers(chatId, offset, limit, filter)

                if (members.isEmpty()) {
                    _state.update { it.copy(canLoadMore = false) }
                } else {
                    offset += members.size
                    _state.update {
                        it.copy(
                            members = it.members + members,
                            canLoadMore = members.size >= limit
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onBack() {
        if (_state.value.isSearchActive) {
            onToggleSearch()
        } else {
            onBackClicked()
        }
    }

    override fun onMemberClick(member: GroupMemberModel) {
        if (type == MemberListComponent.MemberListType.ADMINS) {
            onMemberLongClicked(member.user.id)
        } else {
            onMemberClicked(member.user.id)
        }
    }

    override fun onMemberLongClick(member: GroupMemberModel) = onMemberLongClicked(member.user.id)
    override fun onLoadMore() = loadMembers()

    override fun onSearch(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            if (query.isBlank()) {
                offset = 0
                _state.update { it.copy(members = emptyList(), canLoadMore = true) }
                loadMembers()
            } else {
                _state.update { it.copy(isLoading = true) }
                try {
                    val filter = ChatMembersFilter.Search(query)
                    val results = chatInfoRepository.getChatMembers(chatId, 0, 50, filter)

                    val filtered = when (type) {
                        MemberListComponent.MemberListType.ADMINS -> results.filter { it.status is ChatMemberStatus.Administrator || it.status is ChatMemberStatus.Creator }
                        MemberListComponent.MemberListType.MEMBERS -> results.filter { it.status is ChatMemberStatus.Member }
                        MemberListComponent.MemberListType.BLACKLIST -> results.filter { it.status is ChatMemberStatus.Banned }
                    }

                    _state.update { it.copy(members = filtered, canLoadMore = false) }
                } finally {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    override fun onToggleSearch() {
        val isNowActive = !_state.value.isSearchActive
        _state.update {
            it.copy(
                isSearchActive = isNowActive,
                searchQuery = "",
                members = if (isNowActive) it.members else emptyList(),
                canLoadMore = !isNowActive
            )
        }
        if (!isNowActive) {
            offset = 0
            loadMembers()
        }
    }

    override fun onAddMember() {
    }
}
