package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatMemberStatus
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultAdminManageComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val userId: Long,
    private val onBackClicked: () -> Unit
) : AdminManageComponent, AppComponentContext by context {

    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository
    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository

    private val scope = componentScope
    private val _state = MutableValue(AdminManageComponent.State(chatId = chatId, userId = userId))
    override val state: Value<AdminManageComponent.State> = _state

    init {
        loadMember()
    }

    private fun loadMember() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val chat = chatListRepository.getChatById(chatId)
                val member = chatInfoRepository.getChatMember(chatId, userId)
                val initialStatus = when (val status = member?.status) {
                    is ChatMemberStatus.Administrator -> status
                    is ChatMemberStatus.Creator -> ChatMemberStatus.Administrator(
                        customTitle = member.rank ?: ""
                    )

                    else -> ChatMemberStatus.Administrator(
                        customTitle = member?.rank ?: "",
                        canManageChat = false,
                        canChangeInfo = false,
                        canPostMessages = false,
                        canEditMessages = false,
                        canDeleteMessages = false,
                        canInviteUsers = false,
                        canRestrictMembers = false,
                        canPinMessages = false,
                        canPromoteMembers = false,
                        canManageVideoChats = false,
                        canManageTopics = false,
                        canPostStories = false,
                        canEditStories = false,
                        canDeleteStories = false,
                        canManageDirectMessages = false,
                        isAnonymous = false
                    )
                }
                _state.update {
                    it.copy(
                        member = member,
                        currentStatus = initialStatus,
                        isChannel = chat?.isChannel == true
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onBack() {
        onBackClicked()
    }

    override fun onSave() {
        val status = _state.value.currentStatus ?: return
        _state.update { it.copy(isLoading = true) }
        scope.launch {
            try {
                chatInfoRepository.setChatMemberStatus(chatId, userId, status)
                onBackClicked()
            } catch (e: Exception) {
                e.printStackTrace()
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onTogglePermission(permission: AdminManageComponent.Permission) {
        val current = _state.value.currentStatus as? ChatMemberStatus.Administrator ?: return
        val updated = when (permission) {
            AdminManageComponent.Permission.MANAGE_CHAT -> current.copy(canManageChat = !current.canManageChat)
            AdminManageComponent.Permission.CHANGE_INFO -> current.copy(canChangeInfo = !current.canChangeInfo)
            AdminManageComponent.Permission.POST_MESSAGES -> current.copy(canPostMessages = !current.canPostMessages)
            AdminManageComponent.Permission.EDIT_MESSAGES -> current.copy(canEditMessages = !current.canEditMessages)
            AdminManageComponent.Permission.DELETE_MESSAGES -> current.copy(canDeleteMessages = !current.canDeleteMessages)
            AdminManageComponent.Permission.INVITE_USERS -> current.copy(canInviteUsers = !current.canInviteUsers)
            AdminManageComponent.Permission.RESTRICT_MEMBERS -> current.copy(canRestrictMembers = !current.canRestrictMembers)
            AdminManageComponent.Permission.PIN_MESSAGES -> current.copy(canPinMessages = !current.canPinMessages)
            AdminManageComponent.Permission.MANAGE_TOPICS -> current.copy(canManageTopics = !current.canManageTopics)
            AdminManageComponent.Permission.PROMOTE_MEMBERS -> current.copy(canPromoteMembers = !current.canPromoteMembers)
            AdminManageComponent.Permission.MANAGE_VIDEO_CHATS -> current.copy(canManageVideoChats = !current.canManageVideoChats)
            AdminManageComponent.Permission.POST_STORIES -> current.copy(canPostStories = !current.canPostStories)
            AdminManageComponent.Permission.EDIT_STORIES -> current.copy(canEditStories = !current.canEditStories)
            AdminManageComponent.Permission.DELETE_STORIES -> current.copy(canDeleteStories = !current.canDeleteStories)
            AdminManageComponent.Permission.ANONYMOUS -> current.copy(isAnonymous = !current.isAnonymous)
        }
        _state.update { it.copy(currentStatus = updated) }
    }

    override fun onUpdateCustomTitle(title: String) {
        val current = _state.value.currentStatus as? ChatMemberStatus.Administrator ?: return
        _state.update { it.copy(currentStatus = current.copy(customTitle = title)) }
    }
}
