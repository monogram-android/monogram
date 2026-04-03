package org.monogram.presentation.features.chats.newChat

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.UserModel

interface NewChatComponent {
    val state: StateFlow<State>

    fun onBack()
    fun onUserClicked(userId: Long)
    fun onOpenProfile(userId: Long)
    fun onEditContact(userId: Long, firstName: String, lastName: String)
    fun onRemoveContact(userId: Long)
    fun onSearchQueryChange(query: String)
    fun onCreateGroup()
    fun onCreateChannel()

    // Group/Channel creation
    fun onTitleChange(title: String)
    fun onDescriptionChange(description: String)
    fun onPhotoSelected(path: String?)
    fun onToggleUserSelection(userId: Long)
    fun onAutoDeleteTimeChange(seconds: Int)
    fun onConfirmCreate()
    fun onStepBack()
    fun onConsumeValidationError()

    data class State(
        val contacts: List<UserModel> = emptyList(),
        val searchQuery: String = "",
        val searchResults: List<UserModel> = emptyList(),
        val isLoading: Boolean = false,
        val step: Step = Step.CONTACTS,
        val selectedUserIds: Set<Long> = emptySet(),
        val title: String = "",
        val description: String = "",
        val photoPath: String? = null,
        val autoDeleteTime: Int = 0,
        val isCreating: Boolean = false,
        val validationError: ValidationError? = null
    )

    enum class ValidationError {
        GROUP_TITLE_REQUIRED,
        CHANNEL_TITLE_REQUIRED
    }

    enum class Step {
        CONTACTS, GROUP_MEMBERS, GROUP_INFO, CHANNEL_INFO
    }
}
