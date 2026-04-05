package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultChatPermissionsComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val onBackClicked: () -> Unit
) : ChatPermissionsComponent, AppComponentContext by context {

    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatSettingsRepository: ChatSettingsRepository = container.repositories.chatSettingsRepository
    private val scope = componentScope
    private val _state = MutableValue(ChatPermissionsComponent.State(chatId = chatId))
    override val state: Value<ChatPermissionsComponent.State> = _state

    init {
        loadPermissions()
    }

    private fun loadPermissions() {
        scope.launch {
            val chat = chatListRepository.getChatById(chatId)
            if (chat != null) {
                _state.update { it.copy(permissions = chat.permissions) }
            }
        }
    }

    override fun onBack() = onBackClicked()

    override fun onSave() {
        scope.launch {
            chatSettingsRepository.setChatPermissions(chatId, _state.value.permissions)
            onBackClicked()
        }
    }

    override fun onTogglePermission(permission: ChatPermissionsComponent.Permission) {
        val current = _state.value.permissions
        val updated = when (permission) {
            ChatPermissionsComponent.Permission.SEND_MESSAGES -> current.copy(canSendBasicMessages = !current.canSendBasicMessages)
            ChatPermissionsComponent.Permission.SEND_MEDIA -> current.copy(canSendPhotos = !current.canSendPhotos) // Simplified
            ChatPermissionsComponent.Permission.SEND_STICKERS -> current.copy(canSendOtherMessages = !current.canSendOtherMessages)
            ChatPermissionsComponent.Permission.SEND_POLLS -> current.copy(canSendPolls = !current.canSendPolls)
            ChatPermissionsComponent.Permission.EMBED_LINKS -> current.copy(canAddLinkPreviews =  !current.canAddLinkPreviews)
            ChatPermissionsComponent.Permission.ADD_MEMBERS -> current.copy(canInviteUsers = !current.canInviteUsers)
            ChatPermissionsComponent.Permission.PIN_MESSAGES -> current.copy(canPinMessages = !current.canPinMessages)
            ChatPermissionsComponent.Permission.CHANGE_INFO -> current.copy(canChangeInfo = !current.canChangeInfo)
            ChatPermissionsComponent.Permission.MANAGE_TOPICS -> current.copy(canCreateTopics = !current.canCreateTopics)
        }
        _state.update { it.copy(permissions = updated) }
    }
}
