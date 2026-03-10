package org.monogram.presentation.features.profile.admin

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.ChatPermissionsModel

interface ChatPermissionsComponent {
    val state: Value<State>

    fun onBack()
    fun onSave()
    fun onTogglePermission(permission: Permission)

    data class State(
        val chatId: Long,
        val permissions: ChatPermissionsModel = ChatPermissionsModel(),
        val isLoading: Boolean = false
    )

    enum class Permission {
        SEND_MESSAGES, SEND_MEDIA, SEND_STICKERS, SEND_POLLS,
        EMBED_LINKS, ADD_MEMBERS, PIN_MESSAGES, CHANGE_INFO, MANAGE_TOPICS
    }
}
