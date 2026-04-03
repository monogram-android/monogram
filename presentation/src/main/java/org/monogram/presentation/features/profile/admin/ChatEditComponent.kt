package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.ChatModel

interface ChatEditComponent {
    val state: Value<State>

    fun onBack()
    fun onUpdateTitle(title: String)
    fun onUpdateDescription(description: String)
    fun onUpdateUsername(username: String)
    fun onTogglePublic(isPublic: Boolean)
    fun onToggleTopics(enabled: Boolean)
    fun onToggleAutoTranslate(enabled: Boolean)
    fun onChangeAvatar(path: String)
    fun onSave()
    fun onDeleteChat()

    fun onManageAdmins()
    fun onManageMembers()
    fun onManageBlacklist()
    fun onManagePermissions()

    data class State(
        val chatId: Long,
        val chat: ChatModel? = null,
        val title: String = "",
        val description: String = "",
        val username: String = "",
        val isPublic: Boolean = false,
        val isForum: Boolean = false,
        val isTranslatable: Boolean = false,
        val avatarPath: String? = null,
        val isLoading: Boolean = false,
        val canDelete: Boolean = false
    )
}
