package org.monogram.presentation.features.chats.newChat

import kotlinx.coroutines.flow.StateFlow
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

interface NewChatComponent {
    val state: StateFlow<State>
    val videoPlayerPool: VideoPlayerPool

    fun onBack()
    fun onUserClicked(userId: Long)
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
        val autoDeleteTime: Int = 0
    )

    enum class Step {
        CONTACTS, GROUP_MEMBERS, GROUP_INFO, CHANNEL_INFO
    }
}
