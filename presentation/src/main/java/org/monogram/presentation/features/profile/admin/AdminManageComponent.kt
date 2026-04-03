package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.GroupMemberModel
import org.monogram.domain.repository.ChatMemberStatus

interface AdminManageComponent {
    val state: Value<State>

    fun onBack()
    fun onSave()
    fun onTogglePermission(permission: Permission)
    fun onUpdateCustomTitle(title: String)

    data class State(
        val chatId: Long,
        val userId: Long,
        val member: GroupMemberModel? = null,
        val isLoading: Boolean = false,
        val currentStatus: ChatMemberStatus? = null,
        val isChannel: Boolean = false
    )

    enum class Permission {
        MANAGE_CHAT, CHANGE_INFO, POST_MESSAGES, EDIT_MESSAGES, DELETE_MESSAGES,
        INVITE_USERS, RESTRICT_MEMBERS, PIN_MESSAGES, MANAGE_TOPICS, PROMOTE_MEMBERS,
        MANAGE_VIDEO_CHATS, POST_STORIES, EDIT_STORIES, DELETE_STORIES, ANONYMOUS
    }
}
