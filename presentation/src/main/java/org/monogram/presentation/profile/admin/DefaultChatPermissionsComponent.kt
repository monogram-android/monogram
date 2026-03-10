package org.monogram.presentation.profile.admin

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import org.monogram.domain.repository.ChatsListRepository
import org.monogram.presentation.root.AppComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DefaultChatPermissionsComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val chatsListRepository: ChatsListRepository = context.container.repositories.chatsListRepository,
    private val onBackClicked: () -> Unit
) : ChatPermissionsComponent, AppComponentContext by context {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _state = MutableValue(ChatPermissionsComponent.State(chatId = chatId))
    override val state: Value<ChatPermissionsComponent.State> = _state

    init {
        loadPermissions()
    }

    private fun loadPermissions() {
        scope.launch {
            val chat = chatsListRepository.getChatById(chatId)
            if (chat != null) {
                _state.value = _state.value.copy(permissions = chat.permissions)
            }
        }
    }

    override fun onBack() = onBackClicked()

    override fun onSave() {
        scope.launch {
            chatsListRepository.setChatPermissions(chatId, _state.value.permissions)
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
        _state.value = _state.value.copy(permissions = updated)
    }
}
