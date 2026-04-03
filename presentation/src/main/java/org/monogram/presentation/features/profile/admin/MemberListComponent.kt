package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.GroupMemberModel

interface MemberListComponent {
    val state: Value<State>

    fun onBack()
    fun onMemberClick(member: GroupMemberModel)
    fun onMemberLongClick(member: GroupMemberModel)
    fun onLoadMore()
    fun onSearch(query: String)
    fun onToggleSearch()
    fun onAddMember()

    data class State(
        val chatId: Long,
        val type: MemberListType,
        val members: List<GroupMemberModel> = emptyList(),
        val isLoading: Boolean = false,
        val canLoadMore: Boolean = true,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false
    )

    enum class MemberListType {
        ADMINS, MEMBERS, BLACKLIST
    }
}
