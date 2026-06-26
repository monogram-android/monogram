package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import kotlinx.coroutines.launch
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.domain.repository.ChatInfoRepository
import org.monogram.domain.repository.ChatListRepository
import org.monogram.domain.repository.ChatOperationsRepository
import org.monogram.domain.repository.ChatSettingsRepository
import org.monogram.presentation.core.util.componentScope
import org.monogram.presentation.features.editing.EditorPrimitives
import org.monogram.presentation.root.AppComponentContext

class DefaultChatEditComponent(
    context: AppComponentContext,
    private val chatId: Long,
    private val onBackClicked: () -> Unit,
    private val onManageAdminsClicked: (Long) -> Unit,
    private val onManageMembersClicked: (Long) -> Unit,
    private val onManageBlacklistClicked: (Long) -> Unit
) : ChatEditComponent, AppComponentContext by context {

    private val chatListRepository: ChatListRepository = container.repositories.chatListRepository
    private val chatSettingsRepository: ChatSettingsRepository = container.repositories.chatSettingsRepository
    private val chatOperationsRepository: ChatOperationsRepository = container.repositories.chatOperationsRepository
    private val chatInfoRepository: ChatInfoRepository = container.repositories.chatInfoRepository

    private val scope = componentScope
    private val _state = MutableValue(ChatEditComponent.State(chatId = chatId))
    override val state: Value<ChatEditComponent.State> = _state

    private var initialDescription: String = ""
    private var initialUsername: String = ""
    private var initialIsPublic: Boolean = false
    private var initialPermissions = ChatPermissionsModel()
    private var initialAvatarPath: String? = null
    private var initialHasProtectedContent: Boolean = false
    private var initialSignMessages: Boolean = false
    private var initialJoinToSendMessages: Boolean = false
    private var initialIsForum: Boolean = false
    private var initialLinkedChatId: Long = 0L

    init {
        loadChatData()
    }

    private fun loadChatData() {
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            val chat = chatListRepository.getChatById(chatId)
            val fullInfo = chatInfoRepository.getChatFullInfo(chatId)
            if (chat != null) {
                initialDescription = fullInfo?.description ?: ""
                initialUsername = chat.username ?: ""
                initialIsPublic = !chat.username.isNullOrEmpty()
                initialPermissions = chat.permissions
                initialAvatarPath = chat.avatarPath
                initialHasProtectedContent = chat.hasProtectedContent
                initialSignMessages = chat.signMessages
                initialJoinToSendMessages = chat.joinToSendMessages
                initialIsForum = chat.isForum
                initialLinkedChatId = fullInfo?.linkedChatId ?: 0L
                _state.update {
                    it.copy(
                        chat = chat,
                        title = chat.title,
                        description = fullInfo?.description ?: "",
                        username = chat.username ?: "",
                        isPublic = !chat.username.isNullOrEmpty(),
                        isForum = chat.isForum,
                        linkedChatId = fullInfo?.linkedChatId ?: 0L,
                        hasProtectedContent = chat.hasProtectedContent,
                        signMessages = chat.signMessages,
                        joinToSendMessages = chat.joinToSendMessages,
                        permissions = chat.permissions,
                        avatarPath = chat.avatarPath,
                        canDelete = chat.isAdmin,
                        isLoading = false
                    )
                }
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    override fun onBack() {
        if (_state.value.editor.isDirty) {
            _state.update { it.copy(editor = it.editor.copy(showDiscardChangesDialog = true)) }
        } else {
            onBackClicked()
        }
    }

    override fun onDismissDiscardChanges() {
        _state.update { it.copy(editor = it.editor.copy(showDiscardChangesDialog = false)) }
    }

    override fun onConfirmDiscardChanges() {
        _state.update { it.copy(editor = it.editor.copy(showDiscardChangesDialog = false)) }
        onBackClicked()
    }

    override fun onUpdateTitle(title: String) {
        _state.update {
            it.copy(
                title = title,
                editor = it.editor.copy(isDirty = hasChanges(title = title))
            )
        }
    }

    override fun onUpdateDescription(description: String) {
        _state.update {
            it.copy(
                description = description,
                editor = it.editor.copy(isDirty = hasChanges(description = description))
            )
        }
    }

    override fun onUpdateUsername(username: String) {
        _state.update {
            it.copy(
                username = username,
                editor = it.editor.copy(isDirty = hasChanges(username = username))
            )
        }
    }

    override fun onTogglePublic(isPublic: Boolean) {
        _state.update {
            it.copy(
                isPublic = isPublic,
                editor = it.editor.copy(isDirty = hasChanges(isPublic = isPublic))
            )
        }
    }

    override fun onToggleTopics(enabled: Boolean) {
        _state.update {
            it.copy(
                isForum = enabled,
                editor = it.editor.copy(isDirty = hasChanges(isForum = enabled))
            )
        }
    }

    override fun onTogglePermission(permission: ChatEditComponent.Permission) {
        val updated = _state.value.permissions.toggle(permission)
        _state.update {
            it.copy(
                permissions = updated,
                editor = it.editor.copy(isDirty = hasChanges(permissions = updated))
            )
        }
    }

    override fun onToggleProtectedContent(enabled: Boolean) {
        _state.update {
            it.copy(
                hasProtectedContent = enabled,
                editor = it.editor.copy(isDirty = hasChanges(hasProtectedContent = enabled))
            )
        }
    }

    override fun onToggleSignMessages(enabled: Boolean) {
        _state.update {
            it.copy(
                signMessages = enabled,
                editor = it.editor.copy(isDirty = hasChanges(signMessages = enabled))
            )
        }
    }

    override fun onToggleJoinToSendMessages(enabled: Boolean) {
        _state.update {
            it.copy(
                joinToSendMessages = enabled,
                editor = it.editor.copy(isDirty = hasChanges(joinToSendMessages = enabled))
            )
        }
    }

    override fun onChangeAvatar(path: String) {
        _state.update { it.copy(avatarPath = path, editor = it.editor.copy(isDirty = true)) }
        scope.launch {
            chatSettingsRepository.setChatPhoto(chatId, path)
        }
    }

    override fun onSave() {
        scope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    editor = it.editor.copy(isSaving = true, error = null)
                )
            }
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
            if (currentState.permissions != initialPermissions && currentState.chat?.isChannel != true) {
                chatSettingsRepository.setChatPermissions(chatId, currentState.permissions)
            }
            if (currentState.hasProtectedContent != initialHasProtectedContent) {
                chatSettingsRepository.setChatHasProtectedContent(
                    chatId,
                    currentState.hasProtectedContent
                )
            }
            if (currentState.signMessages != initialSignMessages && currentState.chat?.isChannel == true) {
                chatSettingsRepository.setChatSignMessages(chatId, currentState.signMessages)
            }
            if (currentState.joinToSendMessages != initialJoinToSendMessages && currentState.chat?.isChannel != true) {
                chatSettingsRepository.setChatJoinToSendMessages(
                    chatId,
                    currentState.joinToSendMessages
                )
            }
            if (currentState.isForum != initialIsForum && currentState.chat?.isChannel != true) {
                chatSettingsRepository.toggleChatIsForum(chatId, currentState.isForum)
            }

            initialDescription = currentState.description
            initialUsername = currentState.username
            initialIsPublic = currentState.isPublic
            initialPermissions = currentState.permissions
            initialAvatarPath = currentState.avatarPath
            initialHasProtectedContent = currentState.hasProtectedContent
            initialSignMessages = currentState.signMessages
            initialJoinToSendMessages = currentState.joinToSendMessages
            initialIsForum = currentState.isForum

            _state.update {
                it.copy(
                    isLoading = false,
                    editor = EditorPrimitives.updateState(isDirty = false)
                )
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

    private fun hasChanges(
        title: String = _state.value.title,
        description: String = _state.value.description,
        username: String = _state.value.username,
        isPublic: Boolean = _state.value.isPublic,
        isForum: Boolean = _state.value.isForum,
        hasProtectedContent: Boolean = _state.value.hasProtectedContent,
        signMessages: Boolean = _state.value.signMessages,
        joinToSendMessages: Boolean = _state.value.joinToSendMessages,
        permissions: ChatPermissionsModel = _state.value.permissions
    ): Boolean {
        val chat = _state.value.chat ?: return false
        return title != chat.title ||
                description != initialDescription ||
                username != initialUsername ||
                isPublic != initialIsPublic ||
                isForum != initialIsForum ||
                hasProtectedContent != initialHasProtectedContent ||
                signMessages != initialSignMessages ||
                joinToSendMessages != initialJoinToSendMessages ||
                permissions != initialPermissions ||
                _state.value.avatarPath != initialAvatarPath
    }
}
