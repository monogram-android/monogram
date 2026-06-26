package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.ChatModel
import org.monogram.domain.models.ChatPermissionsModel
import org.monogram.presentation.features.editing.EditorScreenState

interface ChatEditComponent {
    val state: Value<State>

    fun onBack()
    fun onUpdateTitle(title: String)
    fun onUpdateDescription(description: String)
    fun onUpdateUsername(username: String)
    fun onTogglePublic(isPublic: Boolean)
    fun onToggleTopics(enabled: Boolean)
    fun onTogglePermission(permission: Permission)
    fun onToggleProtectedContent(enabled: Boolean)
    fun onToggleSignMessages(enabled: Boolean)
    fun onToggleJoinToSendMessages(enabled: Boolean)
    fun onChangeAvatar(path: String)
    fun onSave()
    fun onDeleteChat()
    fun onDismissDiscardChanges()
    fun onConfirmDiscardChanges()

    fun onManageAdmins()
    fun onManageMembers()
    fun onManageBlacklist()

    data class State(
        val chatId: Long,
        val chat: ChatModel? = null,
        val title: String = "",
        val description: String = "",
        val username: String = "",
        val isPublic: Boolean = false,
        val isForum: Boolean = false,
        val linkedChatId: Long = 0L,
        val hasProtectedContent: Boolean = false,
        val signMessages: Boolean = false,
        val joinToSendMessages: Boolean = false,
        val permissions: ChatPermissionsModel = ChatPermissionsModel(),
        val avatarPath: String? = null,
        val isLoading: Boolean = false,
        val canDelete: Boolean = false,
        val editor: EditorScreenState = EditorScreenState()
    )

    enum class Permission {
        SEND_MESSAGES, SEND_MEDIA, SEND_STICKERS, SEND_POLLS,
        EMBED_LINKS, REACT_TO_MESSAGES, ADD_MEMBERS, PIN_MESSAGES, CHANGE_INFO, MANAGE_TOPICS
    }
}
