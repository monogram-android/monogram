package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.root.AppComponentContext

class DefaultChatEditComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val onBackClicked: () -> Unit,
    private val onManageAdminsClicked: (Long) -> Unit,
    private val onManageMembersClicked: (Long) -> Unit,
    private val onManageBlacklistClicked: (Long) -> Unit,
    private val onManagePermissionsClicked: (Long) -> Unit
) : ChatEditComponent, AppComponentContext by context {

    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatSettingsRepository: ChatSettingsRepository = container.repositories.chatSettingsRepository
    private val chatOperationsRepository: ChatOperationsRepository = container.repositories.chatOperationsRepository
    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository

    private val scope = componentScope
    private val _state = MutableValue(ChatEditComponent.State(chatId = chatId))
    override val state: Value<ChatEditComponent.State> = _state

    init {
        loadChatData()
    }

    private fun loadChatData() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            val chat = chatListRepository.getChatById(chatId)
            val fullInfo = chatInfoRepository.getChatFullInfo(chatId)
            if (chat != null) {
                _state.update {
                    it.copy(
                        chat = chat,
                        title = chat.title,
                        description = fullInfo?.description ?: "",
                        username = chat.username ?: "",
                        isPublic = !chat.username.isNullOrEmpty(),
                        isForum = chat.isForum,
                        isTranslatable = if (chat.isChannel) chat.hasAutomaticTranslation else chat.isTranslatable,
                        avatarPath = chat.avatarPath,
                        canDelete = chat.isAdmin,
                        isLoading = false
                    )
                }
            }
        }
    }

    override fun onBack() = onBackClicked()

    override fun onUpdateTitle(title: String) {
        _state.update { it.copy(title = title) }
    }

    override fun onUpdateDescription(description: String) {
        _state.update { it.copy(description = description) }
    }

    override fun onUpdateUsername(username: String) {
        _state.update { it.copy(username = username) }
    }

    override fun onTogglePublic(isPublic: Boolean) {
        _state.update { it.copy(isPublic = isPublic) }
    }

    override fun onToggleTopics(enabled: Boolean) {
        _state.update { it.copy(isForum = enabled) }
        scope.launch {
            chatSettingsRepository.toggleChatIsForum(chatId, enabled)
        }
    }

    override fun onToggleAutoTranslate(enabled: Boolean) {
        _state.update { it.copy(isTranslatable = enabled) }
        scope.launch {
            chatSettingsRepository.toggleChatIsTranslatable(chatId, enabled)
        }
    }

    override fun onChangeAvatar(path: String) {
        _state.update { it.copy(avatarPath = path) }
        scope.launch {
            chatSettingsRepository.setChatPhoto(chatId, path)
        }
    }

    override fun onSave() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            val currentState = _state.value
            if (currentState.title != currentState.chat?.title) {
                chatSettingsRepository.setChatTitle(chatId, currentState.title)
            }
            val fullInfo = chatInfoRepository.getChatFullInfo(chatId)
            if (currentState.description != (fullInfo?.description ?: "")) {
                chatSettingsRepository.setChatDescription(chatId, currentState.description)
            }
            if (currentState.username != (currentState.chat?.username ?: "")) {
                chatSettingsRepository.setChatUsername(chatId, currentState.username)
            }
            onBackClicked()
        }
    }

    override fun onDeleteChat() {
        scope.launch {
            chatOperationsRepository.deleteChats(setOf(chatId))
            onBackClicked()
        }
    }

    override fun onManageAdmins() = onManageAdminsClicked(chatId)
    override fun onManageMembers() = onManageMembersClicked(chatId)
    override fun onManageBlacklist() = onManageBlacklistClicked(chatId)
    override fun onManagePermissions() = onManagePermissionsClicked(chatId)
}
