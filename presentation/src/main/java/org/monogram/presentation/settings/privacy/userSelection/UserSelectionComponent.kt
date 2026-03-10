package org.monogram.presentation.settings.privacy.userSelection

import com.arkivanov.decompose.value.Value
import org.monogram.domain.models.UserModel
import org.monogram.presentation.features.chats.currentChat.components.VideoPlayerPool

interface UserSelectionComponent {
    val state: Value<State>
    val videoPlayerPool: VideoPlayerPool
    fun onBackClicked()
    fun onSearchQueryChanged(query: String)
    fun onUserClicked(userId: Long)

    data class State(
        val searchQuery: String = "",
        val users: List<UserModel> = emptyList(),
        val isLoading: Boolean = false
    )
}
